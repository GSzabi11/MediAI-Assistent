"""
Beszédfelismerő pipeline – kiinduló prototípus (offline, fájl alapú)

Módok:
  sbo  – orvos–beteg párbeszéd betegfelvételkor (chat + tünetek + kulcsszavak)
  echo – echokardiográfiás szakvizsgálat diktálása (chat + mérések)

Telepítés (Python 3.10+, NVIDIA GPU erősen ajánlott):
  pip install faster-whisper pyannote.audio requests numpy
  # Hugging Face-en fogadd el a feltételeket:
  #   pyannote/speaker-diarization-community-1
  #   pyannote/wespeaker-voxceleb-resnet34-LM
  export HF_TOKEN=hf_...
  # Helyi LLM (adat nem hagyja el a gépet): telepítsd az Ollamát, húzz le egy modellt
  export LLM_MODEL=medgemma:4b   # vagy bármely más letöltött Ollama-modell

Futtatás:
  python beszed_pipeline.py felvetel.wav --mode sbo --doctor-sample orvos_minta.wav
  python beszed_pipeline.py diktalas.wav --mode echo --no-llm
"""
import argparse
import difflib
import json
import os
import re
import time

# Helyi futás, internet nélkül (local_models.py): a Hugging Face Hub offline (csak helyi modellt tölt
# be, nem keres frissítést), és a pyannote / HF telemetria ki — a pyannote 4 alapból az
# otel.pyannote.ai-ra küld használati adatot. A Hub ezeket importáláskor olvassa, ezért itt, az
# importok előtt. Felülírható (a download_models.py online-ra állítja).
for _key, _value in (("HF_HUB_OFFLINE", "1"), ("HF_HUB_DISABLE_TELEMETRY", "1"),
                     ("PYANNOTE_METRICS_ENABLED", "false"), ("DO_NOT_TRACK", "1")):
    os.environ.setdefault(_key, _value)

import numpy as np
import requests
import torch
from faster_whisper import WhisperModel
from pyannote.audio import Inference, Model, Pipeline
from pyannote.core import Segment

import local_models

def _load_env_file():
    """.env betöltése (pl. HF_TOKEN=hf_...). A már beállított környezeti
    változókat nem írja felül. A python-dotenv-et használja, ha telepítve van,
    különben egy egyszerű beépített olvasót."""
    candidates = [os.path.join(os.getcwd(), ".env"),
                  os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"),
                  # a repó gyökerében lévő közös .env (sablon: .env.example)
                  os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".env")]
    for path in dict.fromkeys(candidates):
        if not os.path.isfile(path):
            continue
        try:
            from dotenv import load_dotenv
            load_dotenv(path, override=False)
        except ImportError:
            with open(path, encoding="utf-8-sig") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, value = line.split("=", 1)
                    key = key.strip().removeprefix("export ").strip()
                    value = value.strip().strip('"').strip("'")
                    os.environ.setdefault(key, value)


_load_env_file()
HF_TOKEN = os.environ.get("HF_TOKEN")
# Később cseréld magyarra finomhangolt, CTranslate2-re konvertált modell mappájára
ASR_MODEL = os.environ.get("ASR_MODEL", "large-v3")
LLM_MODEL = os.environ.get("LLM_MODEL", "medgemma:4b")
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434/api/chat")
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

# ---------------------------------------------------------------------------
# 1. MÓDKONFIGURÁCIÓ – minden, ami a két mód között eltér, itt legyen
# ---------------------------------------------------------------------------
MODES = {
    "sbo": {
        # A Whisper prompt úgy működik jól, ha egy korábbi átirat-részletre hasonlít
        "initial_prompt": (
            "Orvos: Jó napot, mi a panasza? Beteg: Tegnap este óta szorító "
            "mellkasi fájdalmam van, nehezen kapok levegőt, és szédülök. "
            "Orvos: Szed rendszeresen gyógyszert? Van allergiája?"
        ),
        "num_speakers": 2,  # hozzátartozó jelenlétében állítsd None-ra
    },
    "echo": {
        "initial_prompt": (
            "Bal kamra végdiasztolés átmérője 52 milliméter, ejekciós frakció "
            "55 százalék. E/A arány 0,8, septalis e' 7 centiméter per "
            "szekundum, E/e' 12. Enyhe mitralis regurgitáció, TAPSE 21 milliméter."
        ),
        "num_speakers": None,  # főleg egy diktáló, de a beteg is megszólalhat
    },
}

SBO_SCHEMA = {
    "type": "object",
    "properties": {
        "fo_panasz": {"type": "string"},
        "tunetek": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "tunet": {"type": "string"},
                    "jelen_van": {"type": "boolean"},  # tagadott tünet: false
                    # null, ha az adott részlet nem hangzott el – ne találja ki a modell
                    "lokalizacio": {"type": ["string", "null"]},
                    "kezdet_idotartam": {"type": ["string", "null"]},
                    "jelleg_sulyossag": {"type": ["string", "null"]},
                    "forras_turn_ids": {"type": "array", "items": {"type": "integer"}},
                },
                "required": ["tunet", "jelen_van", "forras_turn_ids"],
            },
        },
        "gyogyszerek": {"type": "array", "items": {"type": "string"}},
        "allergiak": {"type": "array", "items": {"type": "string"}},
        "elozmenyek": {"type": "array", "items": {"type": "string"}},
        "kulcsszavak": {"type": "array", "items": {"type": "string"}},
    },
    # Mind a hat mező kötelező (üres tömb is jó) – enélkül a modell hajlamos
    # mindent a kulcsszavak közé önteni, a gyógyszer/allergia/előzmény mezőket
    # meg egyszerűen kihagyni, mert "opcionálisnak" érzi őket.
    "required": ["fo_panasz", "tunetek", "gyogyszerek", "allergiak", "elozmenyek", "kulcsszavak"],
}

ECHO_SCHEMA = {
    "type": "object",
    "properties": {
        "meresek": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "parameter": {"type": "string"},
                    "ertek": {"type": "number"},
                    "mertekegyseg": {"type": "string"},
                    "forras_turn_ids": {"type": "array", "items": {"type": "integer"}},
                },
                "required": ["parameter", "ertek", "forras_turn_ids"],
            },
        },
        "megfigyelesek": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["meresek", "megfigyelesek"],
}

SYSTEM_PROMPTS = {
    "sbo": (
        "Sürgősségi osztályos orvos–beteg párbeszéd átiratát kapod, sorszámozott "
        "megszólalásokkal. Feladatod az elhangzott adatok hű kigyűjtése.\n"
        "SZABÁLYOK:\n"
        "1. Csak azt rögzítsd, ami ténylegesen elhangzott. Ne állíts fel diagnózist.\n"
        "2. Ha egy részlet (kezdet, lokalizáció, jelleg/súlyosság) nem hangzott el "
        "az adott tünetről, az értéke legyen null. Soha ne vedd át egy másik tünet "
        "részleteit.\n"
        "3. TAGADÁS: ha az orvos rákérdez egy tünetre, és a beteg tagadja "
        "(pl. 'lázam nem volt'), azt is vedd fel külön tünetként jelen_van=false "
        "értékkel, forrásként a tagadó megszólalással. Ha viszont a kérdésre nem "
        "érkezett válasz, vagy a válasz bizonytalan, azt NE rögzítsd tagadásként.\n"
        "4. Minden tünet külön elem legyen (ne vonj össze kettőt egy perjellel). "
        "A kisugárzást, a terhelésfüggést is rögzítsd a jelleg vagy lokalizáció mezőben.\n"
        "5. forras_turn_ids a BETEG (vagy HOZZÁTARTOZÓ) azon megszólalása(i), "
        "ahol az adat elhangzott – ne az orvos kérdése.\n"
        "6. Gyógyszerek szótári alakban ('ramipril', nem 'ramiprilt') a 'gyogyszerek' "
        "mezőbe; allergiák az 'allergiak' mezőbe; korábbi betegségek, dohányzás, "
        "alkohol az 'elozmenyek' mezőbe. Ezeket ne ismételd a kulcsszavak között.\n"
        "7. Helyes magyar orvosi szóhasználat (pl. 'mellkasi fájdalom', 'nehézlégzés').\n"
        "PÉLDA (más betegről):\n"
        "[0] ORVOS: Fáj a hasa? Hányt?\n"
        "[1] BETEG: Két napja fáj jobb oldalt alul, de hányni nem hánytam. Metformint szedek.\n"
        "→ tunetek: [{\"tunet\": \"hasi fájdalom\", \"jelen_van\": true, "
        "\"lokalizacio\": \"jobb alhas\", \"kezdet_idotartam\": \"két napja\", "
        "\"jelleg_sulyossag\": null, \"forras_turn_ids\": [1]}, "
        "{\"tunet\": \"hányás\", \"jelen_van\": false, \"lokalizacio\": null, "
        "\"kezdet_idotartam\": null, \"jelleg_sulyossag\": null, \"forras_turn_ids\": [1]}], "
        "gyogyszerek: [\"metformin\"]\n"
        "Minden mező legyen kitöltve (üres tömb is megfelelő). Válaszolj magyarul, JSON-ban."
    ),
    "echo": (
        "Echokardiográfiás vizsgálat diktálásának átiratát kapod. Nyerd ki a mért "
        "paramétereket számértékkel és mértékegységgel, a szöveges megfigyeléseket "
        "külön. A betegnek szóló utasításokat (pl. 'forduljon balra') hagyd ki. "
        "Minden méréshez add meg a forrás megszólalás sorszámát. JSON-ban válaszolj."
    ),
}


# ---------------------------------------------------------------------------
# 2. BESZÉDFELISMERÉS – szószintű időbélyegekkel
# ---------------------------------------------------------------------------
def transcribe(path: str, mode: str) -> list[dict]:
    compute_type = "float16" if DEVICE == "cuda" else "int8"
    model = WhisperModel(local_models.asr_model(ASR_MODEL), device=DEVICE, compute_type=compute_type)
    segments, _ = model.transcribe(
        path,
        language="hu",
        word_timestamps=True,
        vad_filter=True,
        beam_size=5,
        initial_prompt=MODES[mode]["initial_prompt"],
    )
    words = []
    for seg in segments:
        for w in seg.words or []:
            words.append({"start": w.start, "end": w.end, "text": w.word})
    return words


# ---------------------------------------------------------------------------
# 3. BESZÉLŐ-SZÉTVÁLASZTÁS (diarizáció)
# ---------------------------------------------------------------------------
def diarize(path: str, mode: str) -> list[tuple[float, float, str]]:
    pipe = Pipeline.from_pretrained(local_models.diarization_model())
    pipe.to(torch.device(DEVICE))
    kwargs = {}
    if MODES[mode]["num_speakers"]:
        kwargs["num_speakers"] = MODES[mode]["num_speakers"]
    out = pipe(path, **kwargs)
    # Az "exclusive" változatban nincs átfedés, így egyszerűbb a szavakhoz rendelés
    return [(t.start, t.end, spk) for t, spk in out.exclusive_speaker_diarization]


def words_to_turns(words: list[dict], diar: list[tuple]) -> list[dict]:
    """Minden szót a középpontját tartalmazó beszélői szakaszhoz rendel,
    majd az egymást követő, azonos beszélőhöz tartozó szavakat összevonja."""
    turns = []
    for w in words:
        mid = (w["start"] + w["end"]) / 2
        spk = next((s for a, b, s in diar if a <= mid <= b), None)
        if spk is None:
            spk = (
                min(diar, key=lambda d: min(abs(mid - d[0]), abs(mid - d[1])))[2]
                if diar else "SPEAKER_00"
            )
        if turns and turns[-1]["speaker"] == spk:
            turns[-1]["text"] += w["text"]
            turns[-1]["end"] = w["end"]
        else:
            turns.append({"speaker": spk, "start": w["start"], "end": w["end"], "text": w["text"]})
    for i, t in enumerate(turns):
        t["id"] = i
        t["text"] = t["text"].strip()
    return turns


# ---------------------------------------------------------------------------
# 4. SZEREPKIOSZTÁS – ki az orvos? (hangminta alapján)
# ---------------------------------------------------------------------------
def _cos(a, b) -> float:
    a, b = np.ravel(a), np.ravel(b)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def assign_roles(path: str, diar: list[tuple], doctor_sample: str | None) -> dict:
    speakers = sorted({s for _, _, s in diar})
    if len(speakers) <= 1:
        return {s: "orvos" for s in speakers}

    if not doctor_sample:
        # Tartalék heurisztika: az orvos rövidebb, gyakoribb megszólalásokat tesz
        counts = {s: sum(1 for *_, x in diar if x == s) for s in speakers}
        doctor = max(counts, key=counts.get)
    else:
        emb_model = Model.from_pretrained(local_models.embedding_model())
        inference = Inference(emb_model, window="whole")
        inference.to(torch.device(DEVICE))
        doc_emb = inference(doctor_sample)
        scores = {}
        for spk in speakers:
            segs = sorted(
                [(a, b) for a, b, s in diar if s == spk and b - a > 1.0],
                key=lambda x: x[1] - x[0], reverse=True,
            )[:5]
            if not segs:
                scores[spk] = -1.0
                continue
            embs = [np.ravel(inference.crop(path, Segment(a, b))) for a, b in segs]
            scores[spk] = _cos(np.mean(embs, axis=0), doc_emb)
        doctor = max(scores, key=scores.get)
        print("Hasonlóság az orvos hangmintájához:", {k: round(v, 3) for k, v in scores.items()})

    # Két beszélőnél a másik a beteg; többnél a legtöbbet beszélő nem-orvos a beteg
    talk = {s: sum(b - a for a, b, x in diar if x == s) for s in speakers if s != doctor}
    patient = max(talk, key=talk.get)
    return {s: "orvos" if s == doctor else "beteg" if s == patient else "egyéb" for s in speakers}


# ---------------------------------------------------------------------------
# 5. MÓDFÜGGŐ KINYERÉS – helyi LLM
# ---------------------------------------------------------------------------
# Mérés alapján (medgemma:latest, RTX 5060 Laptop): a "laza" mód – érvényes JSON
# kényszerítve, a séma szövegesen a promptban – ~8 mp alatt végzett. A "szigorú"
# mód – a teljes beágyazott séma mint dekódolási grammatika – elakadt. Ezért a
# laza mód az alapértelmezett; a mezők meglétét utólag a kód ellenőrzi.
EXTRACT_MODE = os.environ.get("EXTRACT_MODE", "laza")  # "laza" vagy "szigoru"
LLM_DEADLINE = int(os.environ.get("LLM_DEADLINE", "120"))  # mp / próbálkozás


def _schema_description(schema: dict) -> str:
    """A JSON-séma prompt-barát leírása a laza módhoz."""
    return json.dumps(schema, ensure_ascii=False, indent=2)


def _runaway(text: str, window: int = 200) -> bool:
    """Végtelen szóköz/újsor, vagy egy hosszú szakasz többszöri pontos ismétlődése."""
    if len(text) - len(text.rstrip()) > 100:
        return True
    if len(text) < window * 3:
        return False
    tail = text[-window:]
    return text[:-window].count(tail) >= 2


def _call_ollama(model: str, content: str, fmt, deadline: int,
                 keep_loaded: bool, temperature: float = 0.0) -> str:
    """Streamelt hívás valódi határidővel. Ha lejár, a kapcsolat bontása az
    Ollamában is leállítja a generálást, így nem blokkolja a következő kérést."""
    body = {
        "model": model,
        "stream": True,
        "format": fmt,
        "options": {
            "temperature": temperature,
            # Az Ollama alapértelmezett kontextusa rövid; a hosszabb magyar
            # párbeszéd enélkül csendben levágódna.
            "num_ctx": 8192,
            "num_predict": 2048,  # felső korlát a kimenet hosszára
        },
        # 0 = utána azonnal kiüríti a VRAM-ot (8 GB-os GPU-n fontos)
        "keep_alive": "5m" if keep_loaded else 0,
        "messages": [{"role": "user", "content": content}],
    }
    t0 = time.perf_counter()
    parts: list[str] = []
    with requests.post(OLLAMA_URL, json=body, stream=True, timeout=(10, deadline)) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if not line:
                continue
            chunk = json.loads(line)
            if "error" in chunk:
                raise RuntimeError(f"Ollama hiba: {chunk['error']}")
            parts.append(chunk.get("message", {}).get("content", ""))
            if chunk.get("done"):
                break
            if time.perf_counter() - t0 > deadline:
                # a with-blokk elhagyása bontja a kapcsolatot -> az Ollama is leáll
                raise requests.exceptions.Timeout(f"{deadline} mp határidő – kérés megszakítva")
            if len(parts) % 50 == 0 and _runaway("".join(parts)):
                raise RuntimeError("a modell ismétlődő / végtelen kimenetbe ragadt – megszakítva")
    return "".join(parts)


def normalize(result: dict, schema: dict) -> dict:
    """Hiányzó kötelező mezők pótlása és típusok egységesítése, figyelmeztetéssel."""
    warnings = []
    if not isinstance(result, dict):
        raise RuntimeError("a válasz nem JSON objektum")
    for key in schema.get("required", []):
        expected = schema["properties"][key]["type"]
        if key not in result or not isinstance(result[key], list if expected == "array" else str):
            warnings.append(f"Hiányzó vagy hibás típusú mező pótolva: {key}")
            result[key] = [] if expected == "array" else ""
    for item in result.get("tunetek", []):
        if isinstance(item, dict) and isinstance(item.get("jelen_van"), str):
            item["jelen_van"] = item["jelen_van"].strip().lower() in ("true", "igen", "1")
    if warnings:
        result.setdefault("_figyelmeztetesek", []).extend(warnings)
    return result


def extract(chat: list[dict], mode: str, model: str | None = None,
            keep_loaded: bool = False, strict: bool | None = None) -> dict:
    model = model or LLM_MODEL
    strict = (EXTRACT_MODE == "szigoru") if strict is None else strict
    dialog = "\n".join(f"[{t['id']}] {t['role'].upper()}: {t['text']}" for t in chat)
    schema = SBO_SCHEMA if mode == "sbo" else ECHO_SCHEMA
    # A MedGemma (és általában a Gemma) nem kezel külön system promptot,
    # ezért az utasítást a felhasználói üzenet elejére tesszük – ez minden modellnél működik.
    user_msg = f"{SYSTEM_PROMPTS[mode]}\n\n--- ÁTIRAT ---\n{dialog}\n--- ÁTIRAT VÉGE ---"

    if strict:
        fmt, content = schema, user_msg
    else:
        fmt = "json"
        content = (
            f"{user_msg}\n\n--- ELVÁRT JSON SÉMA ---\n{_schema_description(schema)}\n"
            "Csak a fenti sémának megfelelő, érvényes JSON objektumot add vissza, "
            "semmi mást (se magyarázatot, se ```-jelölést)."
        )
    label = "szigorú" if strict else "laza"

    last_err: Exception | None = None
    # 2. próbálkozásnál kicsit magasabb hőmérséklet, hogy ne ugyanazt a hibás kimenetet kapjuk
    for attempt, temp in ((1, 0.0), (2, 0.3)):
        t0 = time.perf_counter()
        try:
            raw = _call_ollama(model, content, fmt, LLM_DEADLINE, keep_loaded, temp)
            result = json.loads(raw)
            print(f"  ({label} mód, {time.perf_counter() - t0:.1f} mp"
                  f"{', 2. próbálkozás' if attempt == 2 else ''})")
            return validate(normalize(result, schema), chat)
        except (json.JSONDecodeError, RuntimeError) as e:
            last_err = e
            if attempt == 1:
                print(f"  Hibás kimenet ({e}) – újrapróbálás...")
    raise last_err


def _content_stems(value: str) -> list[str]:
    """Legalább 4 betűs szavak 3 karakteres töve. Magyar ragozásnál a 4 karakter
    túl szigorú ('karba' vs 'karomba' közös eleje csak 'kar'), és egy téves
    'kitalált' jelzés helyes adatot törölne – ez rosszabb, mint egy elszalasztott."""
    words = re.findall(r"[a-záéíóöőúüű]{4,}", value.lower())
    return [w[:3] for w in words]


def _stem_in(stem: str, text: str) -> bool:
    """A tő szókezdetként szerepel-e a szövegben (ne egyezzen szó belsejében)."""
    return re.search(r"(?<![a-záéíóöőúüű])" + re.escape(stem), text) is not None


def _find_turns(stems: list[str], chat: list[dict]) -> list[int]:
    """Azok a megszólalás-azonosítók, amelyekben a szótövek bármelyike szerepel."""
    return [t["id"] for t in chat if any(_stem_in(st, t["text"].lower()) for st in stems)]


def _check_names(result: dict, chat: list[dict], warnings: list[str]):
    """Gyógyszer- és allergianevek egyeztetése az átirattal: a nyelvi modell nem
    torzíthat el egy elhangzott nevet (pl. 'atorvasztantin')."""
    words = sorted({w for t in chat for w in re.findall(r"[a-záéíóöőúüű]{4,}", t["text"].lower())})
    for field in ("gyogyszerek", "allergiak"):
        for name in result.get(field, []):
            if not isinstance(name, str) or not name.strip():
                continue
            key = name.lower().split()[0]
            # ragozott alak is jó: 'ramipril' ⊂ 'ramiprilt'
            if any(key in w for w in words):
                continue
            close = difflib.get_close_matches(key, words, n=1, cutoff=0.75)
            hint = f" – az átiratban elhangzott alak: '{close[0]}'" if close else ""
            warnings.append(f"NÉV NEM EGYEZIK: '{name}' ({field}) nem szerepel az átiratban{hint}")


def validate(result: dict, chat: list[dict]) -> dict:
    """Ellenőrzi a forrásokat és a részleteket:
    - létező megszólalásra hivatkozik-e minden elem;
    - a tünet részletei elhangzottak-e a hivatkozott megszólalás(ok)ban;
      ha máshol hangzottak el: 'rossz forrás' figyelmeztetés;
      ha sehol: a részlet törlődik (null), az eredeti érték a figyelmeztetésben marad;
    - a gyógyszer- és allergianevek egyeznek-e az elhangzottakkal."""
    by_id = {t["id"]: t for t in chat}
    warnings = []
    # melyik megszólalást melyik tünet(ek) használják forrásként
    cited_by: dict[int, set[int]] = {}
    for idx, it in enumerate(result.get("tunetek", [])):
        if isinstance(it, dict):
            for i in it.get("forras_turn_ids", []) or []:
                cited_by.setdefault(i, set()).add(idx)
    for key in ("tunetek", "meresek"):
        for item_idx, item in enumerate(result.get(key, [])):
            if not isinstance(item, dict):
                warnings.append(f"Nem objektum elem a(z) {key} listában: {item}")
                continue
            ids = item.get("forras_turn_ids", [])
            if not ids or any(i not in by_id for i in ids):
                warnings.append(f"Nincs érvényes forrás: {item}")
                continue
            if key != "tunetek":
                continue
            name = item.get("tunet", "?")
            roles = {by_id[i].get("role") for i in ids}
            if roles <= {"orvos"}:
                warnings.append(f"'{name}': a forrás csak orvosi megszólalás, beteg válasza nélkül")
            # hivatkozott megszólalások + az előttük lévő kérdés
            context_ids = set(ids) | {i - 1 for i in ids if i - 1 in by_id}
            context = " ".join(by_id[i]["text"] for i in sorted(context_ids)).lower()
            for field in ("kezdet_idotartam", "lokalizacio", "jelleg_sulyossag"):
                value = item.get(field)
                if not isinstance(value, str) or not value.strip():
                    continue
                stems = _content_stems(value)
                if not stems or any(_stem_in(st, context) for st in stems):
                    continue
                elsewhere = _find_turns(stems, chat)
                # Ha a részlet csak más tünetek forrásaiban hangzott el, a modell
                # egy másik tünet adatát másolta át (pl. mindenhova 'tegnap este').
                copied = bool(elsewhere) and all(
                    cited_by.get(i, set()) - {item_idx} for i in elsewhere
                )
                if copied:
                    item[field] = None
                    warnings.append(
                        f"ÁTVETT, TÖRÖLVE: '{name}' {field}='{value}' egy másik tünethez "
                        f"hangzott el ({elsewhere}. megszólalás), erre a tünetre nem"
                    )
                elif elsewhere:
                    warnings.append(
                        f"ROSSZ FORRÁS: '{name}' {field}='{value}' nem a megadott "
                        f"{ids} megszólalásban, hanem itt hangzott el: {elsewhere}"
                    )
                else:
                    item[field] = None
                    warnings.append(
                        f"KITALÁLT, TÖRÖLVE: '{name}' {field}='{value}' sehol nem "
                        f"hangzott el az átiratban"
                    )
    _check_names(result, chat, warnings)
    if warnings:
        result.setdefault("_figyelmeztetesek", []).extend(warnings)
    return result


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("--mode", choices=MODES.keys(), required=True)
    ap.add_argument("--doctor-sample", help="30-60 mp-es tiszta hangminta az orvostól")
    ap.add_argument("--no-llm", action="store_true")
    ap.add_argument("--out", default="eredmeny.json")
    args = ap.parse_args()

    print("1/4 Beszédfelismerés...")
    words = transcribe(args.audio, args.mode)
    print("2/4 Beszélő-szétválasztás...")
    diar = diarize(args.audio, args.mode)
    turns = words_to_turns(words, diar)
    print("3/4 Szerepkiosztás...")
    roles = assign_roles(args.audio, diar, args.doctor_sample)

    chat = [
        {"id": t["id"], "role": roles.get(t["speaker"], "egyéb"),
         "start": round(t["start"], 2), "end": round(t["end"], 2), "text": t["text"]}
        for t in turns
    ]
    for c in chat:
        print(f"[{c['start']:>7.1f}s] {c['role'].upper():>6}: {c['text']}")

    result = {"mode": args.mode, "chat": chat}
    if not args.no_llm:
        print("4/4 Kinyerés...")
        result["extraction"] = extract(chat, args.mode)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"Kész: {args.out}")


if __name__ == "__main__":
    main()