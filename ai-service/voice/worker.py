"""A hangszolgáltatás soros feldolgozó sora: egyszerre EGY feladat, a GPU-t pedig a docproc-kal
közös zár (ai-service/gpulock.py) osztja meg. A Spring csak beadja a feladatot és 202-t kap; az
eredmény callbackkel megy vissza (mint a docproc-nál)."""
import logging
import queue
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import requests

import beszed_pipeline as bp
import enrollment
import findings
import keywords
import local_models
import review
import summary
import transcription
import voice_config as config

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from gpulock import gpu_lock  # noqa: E402

log = logging.getLogger("voice.worker")


class JobError(Exception):
    """Várt, az orvosnak is értelmes hiba (FAILED callback az üzenettel)."""


@dataclass(frozen=True, eq=False)
class EnrollmentJob:
    profile_id: int
    sentence_paths: list
    noise_path: str | None
    output_wav: str
    callback_url: str

    @property
    def key(self) -> tuple:
        return ("profile", self.profile_id)

    @property
    def label(self) -> str:
        return f"hangprofil {self.profile_id}"

    def run(self) -> dict:
        paths = [resolve(p) for p in self.sentence_paths]
        noise = resolve(self.noise_path) if self.noise_path else None
        out = resolve(self.output_wav, must_exist=False)
        try:
            return enrollment.enroll([str(p) for p in paths], str(noise) if noise else None, str(out))
        except enrollment.EnrollmentError as e:
            raise JobError(str(e)) from e


@dataclass(frozen=True, eq=False)
class RecordingJob:
    recording_id: int
    audio_path: str
    mode: str  # SBO | ECHO (a Spring enumja)
    doctor_embedding: list
    callback_url: str

    @property
    def key(self) -> tuple:
        return ("recording", self.recording_id)

    @property
    def label(self) -> str:
        return f"felvétel {self.recording_id}"

    def run(self) -> dict:
        mode = self.mode.lower()
        path = resolve(self.audio_path)
        try:
            result = transcription.process(str(path), mode, self.doctor_embedding)
        except transcription.TranscriptionError as e:
            raise JobError(str(e)) from e
        chat, review_model, review_warnings = review.review(result["chat"], mode)
        utterances = []
        for t in chat:
            asr_role = t.get("asr_role")
            utterances.append({
                "turnId": t["id"],
                "speaker": transcription.ROLE_TO_SPEAKER[t["role"]],
                "similarity": t["similarity"],
                "startMs": int(round(t["start"] * 1000)),
                "endMs": int(round(t["end"] * 1000)),
                "text": t["text"],
                "asrText": t.get("asr_text"),
                "asrSpeaker": transcription.ROLE_TO_SPEAKER[asr_role] if asr_role else None,
                "correctionReason": t.get("correction_reason"),
                "reviewNote": t.get("review_note"),
            })
        terms, keyword_model, keyword_warnings = keywords.suggest(chat, mode)
        warnings = [w[:500] for w in result["warnings"] + review_warnings + keyword_warnings][:50]
        return {
            "status": "DONE",
            "asrModel": bp.ASR_MODEL,
            "reviewModel": review_model,
            "utterances": utterances,
            "warnings": warnings,
            "keywords": terms,
            "keywordModel": keyword_model,
        }


@dataclass(frozen=True, eq=False)
class SummaryJob:
    summary_id: int
    mode: str
    utterances: list
    keywords: list
    callback_url: str
    findings: tuple = ()  # a csoportok érvényes elemei (az orvos szerkesztett állapotában)

    @property
    def key(self) -> tuple:
        return ("summary", self.summary_id)

    @property
    def label(self) -> str:
        return f"felvétel-összefoglaló {self.summary_id}"

    def run(self) -> dict:
        return summary.summarize(self.mode, self.utterances, self.keywords, list(self.findings))


@dataclass(frozen=True, eq=False)
class FindingsJob:
    extraction_id: int
    mode: str
    utterances: list
    callback_url: str

    @property
    def key(self) -> tuple:
        return ("findings", self.extraction_id)

    @property
    def label(self) -> str:
        return f"felvétel-csoportok {self.extraction_id}"

    def run(self) -> dict:
        return findings.extract(self.mode, self.utterances)


def resolve(raw: str, must_exist: bool = True) -> Path:
    path = Path(raw).resolve()
    roots = [r for r in (config.VOICE_ROOT, config.DOCUMENTS_ROOT) if r]
    if roots and not any(path.is_relative_to(r) for r in roots):
        raise JobError("A hangfájl a megengedett könyvtáron kívül van")
    if must_exist and not path.is_file():
        raise JobError("A hangfájl nem található")
    return path


class JobQueue:
    def __init__(self) -> None:
        self._queue: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._pending: set[tuple] = set()
        self._current: str | None = None

    def submit(self, job) -> int | None:
        """Sorba állít; a helye a sorban (0 = következő), vagy None, ha már bent van (idempotens)."""
        with self._lock:
            if job.key in self._pending:
                return None
            self._pending.add(job.key)
            self._queue.put(job)
            return self._queue.qsize() - 1

    def status(self) -> dict:
        with self._lock:
            # pending: a sorban álló + futó feladatok kulcsa ("recording:5") — a Spring ebből ismeri fel,
            # ha egy feladata a Python újraindulásakor elveszett (a sor csak memóriában él)
            return {"queued": self._queue.qsize(), "current": self._current,
                    "pending": sorted(f"{k[0]}:{k[1]}" for k in self._pending)}

    def start(self) -> None:
        threading.Thread(target=self._run, name="voice-worker", daemon=True).start()

    def _run(self) -> None:
        while True:
            job = self._queue.get()
            with self._lock:
                self._current = job.label
            started = time.monotonic()
            try:
                with gpu_lock(job.label):
                    payload = job.run()
            except Exception as e:  # noqa: BLE001 — minden hiba FAILED callback, a sor nem állhat le
                log.exception("A(z) %s feldolgozása sikertelen", job.label)
                payload = {"status": "FAILED", "error": _error_message(e)}
            log.info("%s: %s (%.1f s)", job.label, payload["status"], time.monotonic() - started)
            send_callback(job, payload)
            with self._lock:
                self._pending.discard(job.key)
                self._current = None
            self._queue.task_done()


def _error_message(e: Exception) -> str:
    if isinstance(e, (JobError, local_models.ModelMissingError)):
        return str(e)[:2000]
    return f"Váratlan hiba a hangfeldolgozásban: {type(e).__name__}: {e}"[:2000]


def send_callback(job, payload: dict) -> None:
    """Eredmény a Springnek. Hálózati hibánál / 5xx-nél újrapróbál; 4xx-nél a Spring már
    lezárta a feladatot (FAILED), egy rövid FAILED jelzés csak érvénytelen kérésnél megy utána."""
    headers = {"X-Internal-Token": config.TOKEN}
    for attempt, delay in enumerate((0, *config.CALLBACK_RETRY_DELAYS_SEC)):
        if delay:
            time.sleep(delay)
        try:
            resp = requests.post(job.callback_url, json=payload, headers=headers,
                                 timeout=config.CALLBACK_TIMEOUT_SEC)
        except requests.RequestException as e:
            log.warning("Callback sikertelen (%s, %d. próbálkozás): %s", job.label, attempt + 1, e)
            continue
        if resp.status_code < 300:
            log.info("Callback elfogadva: %s → %s", job.label, payload["status"])
            return
        if resp.status_code >= 500:
            log.warning("Callback %d (%s), újrapróbálom", resp.status_code, job.label)
            continue
        log.error("A Spring elutasította az eredményt (%s, HTTP %d): %s",
                  job.label, resp.status_code, resp.text[:1000])
        if payload["status"] != "FAILED" and resp.status_code == 400:
            send_callback(job, {"status": "FAILED", "error": "A hangfeldolgozás eredményét a backend "
                                "elutasította: " + resp.text[:1500]})
        return
    log.error("A(z) %s eredménye nem jutott el a Springhez — PROCESSING-ben marad", job.label)

