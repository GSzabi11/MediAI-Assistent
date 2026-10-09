# docproc — dokumentum-feldolgozó szolgáltatás

A Spring backend által beküldött PDF-ek feldolgozása (szöveg, típus, dátum, labor /
ambuláns adatok, RAG-darabok + BGE-M3 embedding), sorban, egyszerre egy dokumentum.
Interfész és döntések: [`docs/document-processing.md`](../../docs/document-processing.md).

| Fájl | Feladat |
|---|---|
| `app.py` | FastAPI: `POST /jobs`, `POST /summaries`, `POST /chats`, `GET /health` |
| `worker.py` | prioritásos sor (a chat elöl) + szekvenciális feldolgozó szál + callback a Springnek |
| `pdf_text.py` | PDF szöveges rétege (PyMuPDF), OCR nincs |
| `classify.py` | LAB / AMBULANCE — kulcsszó-keresés, nem modell |
| `llm.py` | Ollama-hívás, típusfüggő prompt + JSON-séma, prompt-hash |
| `extract.py` | a modell-kimenet determinisztikus visszakeresése, szám/dátum értelmezés |
| `textutil.py` | normalizálás, idézet → code-point offset |
| `chunking.py`, `embedding.py` | RAG-darabolás, BGE-M3 (1024 dim) |
| `summarize.py` | Klinikai státusz összefoglaló ([`docs/clinical-summary.md`](../../docs/clinical-summary.md)) |
| `chat.py` | Klinikai asszisztens (RAG-chat, [`docs/clinical-chat.md`](../../docs/clinical-chat.md)) |

## Telepítés

```bash
cd ai-service/docproc
python -m venv .venv && .venv\Scripts\activate
# GPU (RTX 50xx: CUDA 12.8+) — előbb a CUDA-s torch, utána a többi:
pip install torch --index-url https://download.pytorch.org/whl/cu128
pip install -r requirements.txt
ollama pull medgemma:4b        # vagy más letöltött modell, ld. DOC_LLM_MODEL
```

Első futáskor a BGE-M3 (~2 GB) letöltődik a Hugging Face-ről.

## Indítás

```bash
set MEDIAI_AI_TOKEN=<közös titok>           # kötelező, ugyanaz, mint a Springnél
python app.py                                # http://127.0.0.1:8001
```

| Változó | Alapérték | |
|---|---|---|
| `MEDIAI_AI_TOKEN` | — (kötelező) | közös titok a Springgel; nélküle nem indul |
| `DOCPROC_HOST` / `DOCPROC_PORT` | `127.0.0.1` / `8001` | |
| `MEDIAI_DOCUMENTS_ROOT` | — | ha meg van adva, csak ez alatti fájl olvasható |
| `OLLAMA_URL` | `http://localhost:11434` | |
| `DOC_LLM_MODEL` | `medgemma:4b` | pl. `gemma4:e4b`, `qwen3.5:9b` |
| `CHAT_LLM_MODEL` | = `DOC_LLM_MODEL` | a chat modellje; ajánlott: `qwen3.5:9b` (ld. clinical-chat.md) |
| `DOC_LLM_SEED` | `42` | a producer-be is bekerül |
| `DOC_LLM_NUM_CTX` | `16384` | az Ollama alapértéke egy leletet levágna |
| `DOC_LLM_MAX_CHARS` | `40000` | ennél hosszabb szöveg vége nem megy a modellhez |
| `EMBEDDING_MODEL` / `EMBEDDING_DEVICE` | `BAAI/bge-m3` / auto | |

A prompt vagy az utófeldolgozás érdemi módosításakor emeld a `config.EXTRACTOR_VERSION`-t:
a prompt-hash változik, így a régi és új kinyerések megkülönböztethetők.

## Mérés (fejlesztői gép, RTX 5060 8 GB, `medgemma:latest`, `docproc-5`)

| Lelet | Idő | Eredmény |
|---|---|---|
| EESZT laborlelet, 37 sor | ~90 s | 37/37 sor (1 cenzorált), mind referenciával, 14 kód |
| Synlab laborlelet, 50 sor | ~105 s | 41/41 numerikus sor, 37 kód; 9 kvalitatív kimarad (szabály szerint) |

Előtte a Synlab-lelet hurok miatt 10 perces időtúllépéssel elbukott (ld. docs/document-processing.md).

## Korábbi mérés (`gemma4:e4b`, egyoldalas szintetikus lelet)

Labor: ~60–75 s, 5/5 numerikus sor, a cenzorált/kvalitatív sorok figyelmeztetéssel kimaradtak.
Ambuláns lap: ~65–80 s, 8 állítás, mindegyik idézete visszakeresve. A beágyazott
(nullázható objektumos) séma 4B-s modellel egyetlen elemet adott vissza — ezért lapos a séma,
mintapéldával.
