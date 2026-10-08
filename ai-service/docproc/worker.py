"""Szekvenciális feldolgozó sor: egyszerre EGY feladat (GPU/VRAM), a többi vár — dokumentum-
feldolgozás, klinikai összefoglaló és chat ugyanabban a sorban. A chat-kérdés elsőbbséget kap
(az orvos vár rá): a sor elejére kerül, de a már futó feladatot megvárja.

A sorbaállítás itt van, nem a Springben: a Spring csak beadja a feladatot és 202-t kap.
Egy dokumentum végén a callback elmegy, és csak utána jön a következő.
"""
import itertools
import logging
import queue
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import requests

import chat
import chunking
import classify
import config
import embedding
import extract
import locate
import summarize
import llm
from pdf_text import NoTextLayerError, extract_text

log = logging.getLogger("docproc")


@dataclass(frozen=True)
class Job:
    document_id: int
    file_path: str
    callback_url: str

    @property
    def key(self) -> tuple:
        return ("document", self.document_id)

    @property
    def label(self) -> str:
        return f"dokumentum {self.document_id}"


@dataclass(frozen=True, eq=False)
class SummaryJob:
    summary_id: int
    callback_url: str
    facts: dict

    @property
    def key(self) -> tuple:
        return ("summary", self.summary_id)

    @property
    def label(self) -> str:
        return f"összefoglaló {self.summary_id}"


@dataclass(frozen=True, eq=False)
class ChatJob:
    chat_id: int
    callback_url: str
    retrieve_url: str
    question: str
    history: list
    facts: list

    @property
    def key(self) -> tuple:
        return ("chat", self.chat_id)

    @property
    def label(self) -> str:
        return f"chat {self.chat_id}"


# kisebb = előbb; azonos prioritáson belül érkezési sorrend
_PRIORITY = {ChatJob: 0}


def run_chat(job: ChatJob) -> dict:
    try:
        chunks = chat.retrieve(job.retrieve_url, job.question, job.history)
    except requests.RequestException as e:
        raise ProcessingError(f"A dokumentumok keresése (Spring retrieve) sikertelen: {e}"[:2000]) from e
    return chat.answer(job.question, job.history, job.facts, chunks)


class ProcessingError(Exception):
    """Várt, a felhasználónak is értelmes hiba (FAILED callback az üzenettel)."""


class JobQueue:
    def __init__(self) -> None:
        self._queue: queue.PriorityQueue[tuple[int, int, Job | SummaryJob | ChatJob]] = queue.PriorityQueue()
        self._seq = itertools.count()
        self._lock = threading.Lock()
        self._pending: set[tuple] = set()  # sorban álló + éppen feldolgozott feladatok
        self._current: str | None = None

    def submit(self, job: "Job | SummaryJob | ChatJob") -> int | None:
        """Sorba állít; a sorban elfoglalt hely (0 = következő), vagy None, ha már bent van
        (ismételt értesítés ugyanarra a dokumentumra nem dolgozza fel kétszer)."""
        priority = _PRIORITY.get(type(job), 1)
        with self._lock:
            if job.key in self._pending:
                return None
            self._pending.add(job.key)
            position = sum(1 for p, _, _ in list(self._queue.queue) if p <= priority)
            self._queue.put((priority, next(self._seq), job))
            return position

    def status(self) -> dict:
        with self._lock:
            return {"queued": self._queue.qsize(), "current": self._current}

    def start(self) -> None:
        threading.Thread(target=self._run, name="docproc-worker", daemon=True).start()

    def _run(self) -> None:
        while True:
            _, _, job = self._queue.get()
            with self._lock:
                self._current = job.label
            try:
                if isinstance(job, ChatJob):
                    payload = run_chat(job)
                elif isinstance(job, SummaryJob):
                    payload = summarize.summarize(job.facts)
                else:
                    payload = process(job)
            except Exception as e:  # noqa: BLE001 — minden hiba FAILED callback, a sor nem állhat le
                log.exception("A(z) %s feldolgozása sikertelen", job.label)
                payload = {"status": "FAILED", "error": _error_message(e)}
            send_callback(job, payload)
            with self._lock:
                self._pending.discard(job.key)
                self._current = None
            self._queue.task_done()


def _error_message(e: Exception) -> str:
    if isinstance(e, (ProcessingError, NoTextLayerError)):
        return str(e)
    if isinstance(e, requests.RequestException):
        return f"Az Ollama-hívás sikertelen ({config.LLM_MODEL}): {e}"[:2000]
    return f"Váratlan hiba a feldolgozásban: {type(e).__name__}: {e}"[:2000]


def _resolve_path(raw: str) -> Path:
    path = Path(raw).resolve()
    if config.DOCUMENTS_ROOT and not path.is_relative_to(config.DOCUMENTS_ROOT):
        raise ProcessingError("A fájl a megengedett dokumentum-könyvtáron kívül van")
    if not path.is_file():
        raise ProcessingError("A dokumentum fájlja nem található")
    return path


def process(job: Job) -> dict:
    started = time.monotonic()
    warnings: list[str] = []

    pdf_path = _resolve_path(job.file_path)
    text = extract_text(pdf_path)
    doc_type = classify.classify(text)  # determinisztikus, nem modell
    log.info("Dokumentum %s: %s, %d karakter", job.document_id, doc_type, len(text))

    llm_text = text
    # A laborlelet szakaszonként megy a modellhez (llm.lab_segments), ott nincs vágás
    if doc_type != classify.LAB and len(text) > config.LLM_MAX_CHARS:
        llm_text = text[:config.LLM_MAX_CHARS]
        warnings.append(f"A dokumentum hosszú: csak az első {config.LLM_MAX_CHARS} karaktere került a modellhez")
    data, llm_warnings = llm.extract(doc_type, llm_text)
    warnings.extend(llm_warnings)

    clinical_date = extract.resolve_clinical_date(data.get("datum"), text, warnings)
    if clinical_date is None:
        raise ProcessingError("Nem állapítható meg megbízhatóan a dokumentum klinikai dátuma "
                              "(nincs a szövegben, vagy több jelölt van)")

    lab_results: list[dict] = []
    statements: list[dict] = []
    if doc_type == classify.LAB:
        lab_results = extract.lab_results(data, text, warnings)
        # MERRE: az eredmény sora a PDF lapján (a frontend kiemeli)
        locate.add_source_boxes(pdf_path, lab_results)
        missing = sum(1 for r in lab_results if r["sourceBox"] is None)
        if missing:
            warnings.append(f"{missing} laborsor helye nem található a PDF-ben (kiemelés nélkül jelenik meg)")
    else:
        statements = extract.ambulance_statements(data, text, warnings)

    ranges = chunking.chunk_ranges(text)
    vectors = embedding.embed([text[s:e] for s, e in ranges])

    log.info("Dokumentum %s kész: %d laborsor, %d állítás, %d darab, %d figyelmeztetés, %.1f s",
             job.document_id, len(lab_results), len(statements), len(ranges), len(warnings),
             time.monotonic() - started)
    return {
        "status": "PROCESSED",
        "documentType": doc_type,
        "extractedText": text,
        "clinicalDate": clinical_date.isoformat(),
        "extraction": {
            "modelName": config.LLM_MODEL,
            "seed": config.LLM_SEED,
            "promptHash": llm.prompt_hash(doc_type),
            "extractorVersion": config.EXTRACTOR_VERSION,
        },
        "chunks": {
            "embeddingModel": config.EMBEDDING_MODEL,
            "chunks": [{"charStart": s, "charEnd": e, "embedding": v} for (s, e), v in zip(ranges, vectors)],
        },
        "labResults": lab_results,
        "statements": statements,
        "warnings": [w[:500] for w in warnings],
    }


def send_callback(job: "Job | SummaryJob | ChatJob", payload: dict) -> None:
    """Eredmény a Springnek. Hálózati hibánál / 5xx-nél újrapróbál. 4xx-nél a Spring már
    FAILED-re állította a feladatot (vagy már lezárt) — egy rövid FAILED jelzés biztosítja,
    hogy olvashatatlan kérés esetén se maradjon PROCESSING-ben."""
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
            send_callback(job, {"status": "FAILED", "error": "A feldolgozás eredményét a backend elutasította: "
                                + resp.text[:1500]})
        return
    log.error("A(z) %s eredménye nem jutott el a Springhez — PROCESSING-ben marad", job.label)
