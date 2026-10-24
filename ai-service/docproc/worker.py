"""Szekvenciális feldolgozó sor: egyszerre EGY feladat (GPU/VRAM), a többi vár — dokumentum-
feldolgozás, klinikai összefoglaló és chat ugyanabban a sorban. A chat-kérdés elsőbbséget kap
(az orvos vár rá): a sor elejére kerül, de a már futó feladatot megvárja.

A sorbaállítás itt van, nem a Springben: a Spring csak beadja a feladatot és 202-t kap.
Egy dokumentum végén a callback elmegy, és csak utána jön a következő.
"""
import itertools
import logging
import queue
import sys
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
import epicrisis
import extract
import history
import literature
import locate
import research
import summarize
import llm
from pdf_text import NoTextLayerError, extract_text

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from gpulock import gpu_lock  # noqa: E402

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
class MedicalHistoryJob:
    history_id: int
    callback_url: str
    facts: dict
    # haladás a felületnek ("Dokumentum-kivonat 5/16"); None: régi Spring, nem küldjük
    progress_url: str | None = None

    @property
    def key(self) -> tuple:
        return ("medical-history", self.history_id)

    @property
    def label(self) -> str:
        return f"kórtörténet {self.history_id}"


@dataclass(frozen=True, eq=False)
class EpicrisisJob:
    epicrisis_id: int
    callback_url: str
    facts: dict
    progress_url: str | None = None

    @property
    def key(self) -> tuple:
        return ("epicrisis", self.epicrisis_id)

    @property
    def label(self) -> str:
        return f"epikrízis {self.epicrisis_id}"


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


@dataclass(frozen=True, eq=False)
class ResearchContextJob:
    context_id: int
    callback_url: str
    facts: dict

    @property
    def key(self) -> tuple:
        return ("research-context", self.context_id)

    @property
    def label(self) -> str:
        return f"kutatási összefoglaló {self.context_id}"


@dataclass(frozen=True, eq=False)
class ArticleEvalJob:
    article_id: int
    callback_url: str
    article: dict
    context_text: str
    extra_prompt: str | None

    @property
    def key(self) -> tuple:
        return ("article", self.article_id)

    @property
    def label(self) -> str:
        return f"cikk-értékelés {self.article_id}"


@dataclass(frozen=True, eq=False)
class RankingJob:
    """Egy keresés összes találatának AI-rangsora (előszűrés + a legrelevánsabb kiválasztása). EGY feladat
    a sorban (~3 s/cikk): közben a chat is vár rá — cserébe a rangsor egyben, konzisztensen készül el."""
    research_id: int
    callback_url: str
    progress_url: str
    articles: list
    context_text: str
    extra_prompt: str | None

    @property
    def key(self) -> tuple:
        return ("ranking", self.research_id)

    @property
    def label(self) -> str:
        return f"AI-rangsor {self.research_id}"


@dataclass(frozen=True, eq=False)
class SearchJob:
    """Hálózati keresés (PubMed, iCite) — GPU nem kell, ezért NEM a sorban fut: egy éppen
    feldolgozott lelet nem tartja fel. Saját szálon, egyszerre egy keresés."""
    research_id: int
    callback_url: str
    progress_url: str
    query: str
    sources: list
    max_results: int

    @property
    def key(self) -> tuple:
        return ("search", self.research_id)

    @property
    def label(self) -> str:
        return f"keresés {self.research_id}"


# kisebb = előbb; azonos prioritáson belül érkezési sorrend. A cikk-értékelésre az orvos a képernyő
# előtt vár (mint a chatnél), de egy oldalnyi cikk hosszabb — a chat után, a lelet előtt jön.
_PRIORITY = {ChatJob: 0, ArticleEvalJob: 1, RankingJob: 1}


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
        priority = _PRIORITY.get(type(job), 2)
        with self._lock:
            if job.key in self._pending:
                return None
            self._pending.add(job.key)
            position = sum(1 for p, _, _ in list(self._queue.queue) if p <= priority)
            self._queue.put((priority, next(self._seq), job))
            return position

    def status(self) -> dict:
        with self._lock:
            # pending: a sorban álló + futó feladatok kulcsa ("medical-history:5") — a Spring ebből ismeri fel,
            # ha egy feladata a Python újraindulásakor elveszett (a sor csak memóriában él)
            return {"queued": self._queue.qsize(), "current": self._current,
                    "pending": sorted(f"{k[0]}:{k[1]}" for k in self._pending)}

    def start(self) -> None:
        threading.Thread(target=self._run, name="docproc-worker", daemon=True).start()

    def _run(self) -> None:
        while True:
            _, _, job = self._queue.get()
            with self._lock:
                self._current = job.label
            try:
                # a hangszolgáltatással (ai-service/voice) közös GPU-zár: egyszerre egy GPU-feladat a gépen
                with gpu_lock(job.label):
                    if isinstance(job, ChatJob):
                        payload = run_chat(job)
                    elif isinstance(job, SummaryJob):
                        payload = summarize.summarize(job.facts)
                    elif isinstance(job, MedicalHistoryJob):
                        payload = history.build(job.facts, lambda m, step, total, model=None: send_step(job, m, step, total, model))
                    elif isinstance(job, EpicrisisJob):
                        payload = epicrisis.build(job.facts, lambda m, step, total: send_step(job, m, step, total))
                    elif isinstance(job, ResearchContextJob):
                        payload = research.context(job.facts)
                    elif isinstance(job, ArticleEvalJob):
                        payload = research.evaluate(job.article, job.context_text, job.extra_prompt)
                    elif isinstance(job, RankingJob):
                        payload = research.rank(job.articles, job.context_text, job.extra_prompt,
                                                lambda message: send_progress(job, message))
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


class SearchRunner:
    """A keresések saját sora és szála (hálózat, nem GPU)."""

    def __init__(self) -> None:
        self._queue: queue.Queue[SearchJob] = queue.Queue()
        self._lock = threading.Lock()
        self._pending: set[tuple] = set()

    def submit(self, job: SearchJob) -> int | None:
        with self._lock:
            if job.key in self._pending:
                return None
            self._pending.add(job.key)
            self._queue.put(job)
            return self._queue.qsize() - 1

    def start(self) -> None:
        threading.Thread(target=self._run, name="docproc-search", daemon=True).start()

    def _run(self) -> None:
        while True:
            job = self._queue.get()
            try:
                total, articles = literature.search(job.query, job.sources, job.max_results,
                                                    lambda message: send_progress(job, message))
                # a Spring "abstractText"-et vár (az "abstract" Java-ban foglalt szó)
                payload = {"status": "DONE", "totalFound": total,
                           "articles": [{**{k: v for k, v in a.items() if k != "abstract"},
                                         "abstractText": a.get("abstract")} for a in articles]}
                log.info("Keresés %s kész: %d / %d cikk", job.research_id, len(articles), total)
            except Exception as e:  # noqa: BLE001 — minden hiba FAILED callback
                log.exception("A(z) %s sikertelen", job.label)
                payload = {"status": "FAILED", "error": _error_message(e)}
            send_callback(job, payload)
            with self._lock:
                self._pending.discard(job.key)
            self._queue.task_done()


def send_step(job: "MedicalHistoryJob | EpicrisisJob", message: str, step: int, total: int,
              model: str | None = None) -> None:
    """Lépésenkénti haladás a Springnek (a kórtörténet); ha nem megy át, a feldolgozás ettől folytatódik."""
    if not job.progress_url:
        return
    try:
        requests.post(job.progress_url, json={"message": message[:500], "step": step, "total": total, "model": model},
                      headers={"X-Internal-Token": config.TOKEN}, timeout=10)
    except requests.RequestException as e:
        log.warning("Állapotüzenet nem ment át (%s): %s", job.label, e)


def send_progress(job: "SearchJob | RankingJob", message: str) -> None:
    """Állapotüzenet a Springnek; ha nem megy át, a keresés / rangsorolás ettől még folytatódik."""
    try:
        requests.post(job.progress_url, json={"message": message[:500]},
                      headers={"X-Internal-Token": config.TOKEN}, timeout=10)
    except requests.RequestException as e:
        log.warning("Állapotüzenet nem ment át (%s): %s", job.label, e)


def _error_message(e: Exception) -> str:
    if isinstance(e, (ProcessingError, NoTextLayerError, literature.LiteratureError)):
        return str(e)
    if isinstance(e, requests.RequestException):
        return f"Az Ollama-hívás sikertelen: {e}"[:2000]
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
