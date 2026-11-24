"""H) DER — beszélő-szétválasztás (diarizáció) és szerepkiosztás hibája egy felolvasott kétszereplős párbeszéden.

Referencia: kézi annotáció Audacity-címkesávként (`kezdet<TAB>vég<TAB>címke`, címke O = orvos, B = beteg;
átfedésnél két, időben egymásba lógó címke). A forgatókönyv (der_forgatokonyv.md) csak ellenőrzésre szolgál
(a megszólalások sorrendje), a referencia időpontjai mindig a kézi annotációból jönnek.

Rendszer-kimenet (hipotézis): a felvétel megszólalásai az adatbázisból (`utterance.start_ms/end_ms`), kétféle
beszélővel: `asr_speaker` (a diarizáció + hanglenyomat szerinti, az AI-átnézés ELŐTT) és `speaker` (az átnézés után).

Mérőszámok (pyannote.metrics):
  - DER  — szabványos, a beszélő-címkék optimális (Hungarian) párosításával: hiány + téves riasztás + összetévesztés.
  - SzKH — szerepkiosztási hiba: ugyanez, de RÖGZÍTETT párosítással (O = DOCTOR, B = PATIENT) — a klinikailag
           érdekes szám (rossz szerephez írt mondat), ez az IdentificationErrorRate.
  Mindkettő collar ±0,25 s-mal (NIST md-eval szokás; pyannote-ban collar=0.5) és collar nélkül, átfedéssel és
  átfedés nélkül. Emellett megszólalásonként: a rendszer megszólalásának szerepe egyezik-e a referenciában
  időben legnagyobb részben átfedő beszélővel.
  --nyers: a pyannote-pipeline saját kimenete ugyanazon a hangon (num_speakers=2, mint a kétbeszélős feldolgozásban), az
           átfedést is jelölő és a kizárólagos változat — irodalommal összevethető DER (a szóhoz rendelés nélkül).

Használat (a repó gyökeréből; a Postgres-konténer fut; a hangmodul Pythonjával, mert az tud hangot dekódolni):
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\der.py lista --patient 30
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\der.py elokeszit --recording 41
      → eredmenyek/der/rec41.wav (16 kHz mono, Audacity-be), rec41_vazlat.txt (Silero VAD beszédszakaszok "?"
        címkével — a diarizációtól független váz az annotáláshoz), rec41_rendszer.txt (a rendszer kimenete,
        CSAK az annotálás UTÁN nyisd meg, különben a kézi referencia a rendszerhez igazodik)
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\der.py pontoz --recording 41 \\
      --ref tools\\meresek_2026-11\\eredmenyek\\der\\rec41_ref.txt [--nyers] [--verbose]

A kimenetbe csak szám és a (kitalált) forgatókönyv szövege kerül; a hangfájl az eredmenyek/der/ alatt marad
(gitignore: a felolvasók hangja biometrikus adat).
"""
import argparse
import difflib
import re
import sys
import wave
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import HERE, ROOT, save_json, sql  # noqa: E402

SCRIPT = HERE / "der_forgatokonyv.md"
OUT = HERE / "eredmenyek" / "der"
VOICE_DIR = ROOT / "ai-service" / "voice"
SR = 16000
ROLE = {"O": "DOCTOR", "B": "PATIENT"}
COLLARS = (0.5, 0.0)  # pyannote: a teljes szélesség → ±0,25 s és 0


# --------------------------------------------------------------------------- beolvasás
def load_script() -> list[dict]:
    """A forgatókönyv sorai: [{id, speaker, text, overlaps: [(speaker, text)]}]."""
    turns = []
    for line in SCRIPT.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([OB])(\d+):\s*(.+)$", line.strip())
        if not m:
            continue
        spk, num, body = m.groups()
        overlaps = [(s, t.strip()) for s, t in re.findall(r"‹([OB]):\s*(.*?)›", body)]
        text = re.sub(r"\s*‹[OB]:.*?›\s*", " ", body).strip()
        turns.append({"id": f"{spk}{num}", "speaker": spk, "text": text, "overlaps": overlaps, "raw": body})
    return turns


def load_labels(path: Path) -> list[tuple[float, float, str]]:
    """Audacity-címkesáv (tabulátorral; a `\\` kezdetű frekvencia-sorok kimaradnak)."""
    out = []
    for n, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip() or line.startswith("\\"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            sys.exit(f"{path.name}:{n}: nem Audacity-címke (kezdet<TAB>vég<TAB>címke): {line!r}")
        a, b, lab = float(parts[0].replace(",", ".")), float(parts[1].replace(",", ".")), parts[2].strip().upper()
        if b <= a:
            print(f"  figyelem: {path.name}:{n} nulla / negatív hosszú címke, kihagyva")
            continue
        out.append((a, b, lab))
    return sorted(out)


def recording_meta(rid: int) -> dict:
    rows = sql(f"select id, mode, status, duration_sec, audio_path, audio_deleted_at from recording where id = {rid}")
    if not rows:
        sys.exit(f"Nincs {rid} azonosítójú felvétel.")
    return rows[0]


def hyp_segments(rid: int) -> list[dict]:
    return sql(f"select turn_id, start_ms, end_ms, speaker, coalesce(asr_speaker, speaker) as asr_speaker, "
               f"similarity, text from utterance where recording_id = {rid} order by turn_id")


def audio_file(meta: dict, override: str | None) -> Path:
    if override:
        return Path(override)
    if not meta.get("audio_path"):
        sys.exit("A felvételnek nincs megőrzött hangja (audio_path üres) — add meg --audio-val.")
    for base in (ROOT / "backend" / "data" / "documents", ROOT / "data" / "documents"):
        p = base / meta["audio_path"]
        if p.exists():
            return p
    sys.exit(f"Nem található a hangfájl: {meta['audio_path']} (backend/data/documents alatt) — add meg --audio-val.")


def decode(path: Path):
    import numpy as np
    from faster_whisper.audio import decode_audio
    return decode_audio(str(path), sampling_rate=SR).astype(np.float32)


def write_wav(path: Path, audio) -> None:
    import numpy as np
    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm)


def write_labels(path: Path, segs) -> None:
    path.write_text("".join(f"{a:.3f}\t{b:.3f}\t{lab}\n" for a, b, lab in segs), encoding="utf-8")


# --------------------------------------------------------------------------- előkészítés
def cmd_prepare(a) -> None:
    meta = recording_meta(a.recording)
    if meta["status"] != "DONE":
        print(f"figyelem: a felvétel állapota {meta['status']}, nem DONE")
    src = audio_file(meta, a.audio)
    OUT.mkdir(parents=True, exist_ok=True)
    audio = decode(src)
    wav = OUT / f"rec{a.recording}.wav"
    write_wav(wav, audio)
    print(f"hang: {wav} ({len(audio) / SR:.1f} s, mód: {meta['mode']})")

    import torch
    from silero_vad import get_speech_timestamps, load_silero_vad
    ts = get_speech_timestamps(torch.from_numpy(audio), load_silero_vad(), sampling_rate=SR,
                               min_silence_duration_ms=150, speech_pad_ms=30)
    draft = OUT / f"rec{a.recording}_vazlat.txt"
    write_labels(draft, [(t["start"] / SR, t["end"] / SR, "?") for t in ts])
    print(f"annotálási váz (Silero VAD, {len(ts)} szakasz): {draft}")

    hyp = hyp_segments(a.recording)
    sysf = OUT / f"rec{a.recording}_rendszer.txt"
    write_labels(sysf, [(h["start_ms"] / 1000, h["end_ms"] / 1000,
                         f"{h['speaker'][0]}{'' if h['speaker'] == h['asr_speaker'] else '*'} #{h['turn_id']}")
                        for h in hyp])
    print(f"rendszer-kimenet ({len(hyp)} megszólalás): {sysf}  ← csak az annotálás UTÁN nyisd meg")
    ref = OUT / f"rec{a.recording}_ref.txt"
    print(f"\nKövetkező lépés: annotáld a {wav.name}-t Audacityben, és exportáld a címkéket ide: {ref}")


def cmd_list(a) -> None:
    rows = sql(f"select r.id, r.mode, r.status, r.duration_sec, r.recorded_at, "
               f"(select count(*) from utterance u where u.recording_id = r.id) as utterances "
               f"from recording r where r.patient_id = {a.patient} order by r.recorded_at, r.id")
    print("| Felvétel | Mód | Állapot | Hossz (s) | Megszólalás | Rögzítve |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['id']} | {r['mode']} | {r['status']} | {r['duration_sec']} | {r['utterances']} | {r['recorded_at']} |")


# --------------------------------------------------------------------------- pontozás
def annotation(segs, label_map=None):
    from pyannote.core import Annotation, Segment
    ann = Annotation()
    for i, (a, b, lab) in enumerate(segs):
        ann[Segment(a, b), i] = label_map.get(lab, lab) if label_map else lab
    return ann


def metrics(ref, hyp, uem) -> dict:
    from pyannote.metrics.diarization import DiarizationErrorRate
    from pyannote.metrics.identification import IdentificationErrorRate
    out = {}
    for collar in COLLARS:
        for skip in (False, True):
            key = f"collar{collar / 2:g}_{'atfedes_nelkul' if skip else 'atfedessel'}"
            d = DiarizationErrorRate(collar=collar, skip_overlap=skip)(ref, hyp, uem=uem, detailed=True)
            i = IdentificationErrorRate(collar=collar, skip_overlap=skip)(ref, hyp, uem=uem, detailed=True)
            out[key] = {
                "total": round(d["total"], 2),
                "der": round(d["diarization error rate"], 4),
                "missed": round(d["missed detection"], 2), "false_alarm": round(d["false alarm"], 2),
                "confusion": round(d["confusion"], 2),
                "role_err": round(i["identification error rate"], 4), "role_confusion": round(i["confusion"], 2),
            }
    return out


def ref_stats(ref_segs) -> dict:
    ann = annotation(ref_segs)
    speech = ann.get_timeline().support().duration()
    ov = ann.get_overlap()
    per = {lab: round(ann.label_duration(lab), 1) for lab in ann.labels()}
    return {"segments": len(ref_segs), "speech_sec": round(speech, 1), "overlap_sec": round(ov.duration(), 1),
            "per_speaker_sec": per}


def main_sequence(ref_segs) -> list[str]:
    """A referencia fő megszólalásai: a másik beszélő szakaszába teljesen beleeső (átfedő betét) kimarad,
    az egymást követő azonos beszélő összevonódik."""
    main = [s for s in ref_segs
            if not any(o is not s and o[2] != s[2] and o[0] <= s[0] and s[1] <= o[1] for o in ref_segs)]
    seq = []
    for _, _, lab in sorted(main):
        if not seq or seq[-1] != lab:
            seq.append(lab)
    return seq


def script_sequence(script) -> list[str]:
    seq = []
    for t in script:
        if not seq or seq[-1] != t["speaker"]:
            seq.append(t["speaker"])
    return seq


def utterance_check(hyp, ref_segs, field: str) -> dict:
    """A rendszer minden megszólalása: a vele időben legtöbbet átfedő referencia-beszélő szerepe = a sajátja?"""
    ok, wrong, nospeech, rows = 0, 0, 0, []
    for h in hyp:
        a, b = h["start_ms"] / 1000, h["end_ms"] / 1000
        cover = {}
        for ra, rb, lab in ref_segs:
            ov = min(b, rb) - max(a, ra)
            if ov > 0:
                cover[lab] = cover.get(lab, 0) + ov
        if not cover:
            nospeech += 1
            rows.append({"turn_id": h["turn_id"], "start": round(a, 2), "hiba": "nincs referencia-beszéd"})
            continue
        best = max(cover, key=cover.get)
        exp = ROLE.get(best, "OTHER")
        if h[field] == exp:
            ok += 1
        else:
            wrong += 1
            rows.append({"turn_id": h["turn_id"], "start": round(a, 2), "end": round(b, 2), "rendszer": h[field],
                         "referencia": exp, "dur": round(b - a, 2), "similarity": h.get("similarity"),
                         "text": h.get("text", "")[:80]})
    n = ok + wrong + nospeech
    return {"n": n, "correct": ok, "wrong": wrong, "no_ref_speech": nospeech,
            "accuracy": round(ok / n, 4) if n else None, "_rows": rows}


def raw_diarization(audio_path: Path, num_speakers: int):
    """A pyannote-pipeline saját kimenete (a hangmodul helyi modelljével, offline)."""
    import torch
    sys.path.insert(0, str(VOICE_DIR))
    import beszed_pipeline as bp  # noqa: F401 — offline környezeti változók (HF_HUB_OFFLINE, telemetria ki)
    import local_models
    from pyannote.audio import Pipeline
    audio = decode(audio_path)
    pipe = Pipeline.from_pretrained(local_models.diarization_model())
    pipe.to(torch.device(bp.DEVICE))
    out = pipe({"waveform": torch.from_numpy(audio)[None, :], "sample_rate": SR}, num_speakers=num_speakers)
    return out.speaker_diarization, out.exclusive_speaker_diarization


def cmd_score(a) -> None:
    from pyannote.core import Segment, Timeline
    meta = recording_meta(a.recording)
    ref_segs = load_labels(Path(a.ref))
    unknown = sorted({lab for _, _, lab in ref_segs} - set(ROLE))
    if unknown:
        print(f"figyelem: ismeretlen címke(k) a referenciában: {unknown} (egyéb beszélőként számolva)")
    hyp = hyp_segments(a.recording)
    if not hyp:
        sys.exit("A felvételnek nincsenek megszólalásai.")
    duration = float(meta["duration_sec"] or 0) or max(max(b for _, b, _ in ref_segs), hyp[-1]["end_ms"] / 1000)
    uem = Timeline([Segment(0, duration)])

    # 1) a kézi referencia ellenőrzése a forgatókönyvvel (sorrend)
    script = load_script()
    exp, got = script_sequence(script), main_sequence(ref_segs)
    ratio = difflib.SequenceMatcher(a=exp, b=got, autojunk=False).ratio()
    seq_info = {"script_turns": len(exp), "ref_turns": len(got), "match_ratio": round(ratio, 4),
                "script_overlaps": sum(len(t["overlaps"]) for t in script)}
    print(f"Forgatókönyv: {len(exp)} fő megszólalás ({seq_info['script_overlaps']} átfedő betét); a referencia "
          f"fő megszólalásai: {len(got)}; sorrend-egyezés: {100 * ratio:.1f}%")
    if ratio < 1:
        for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=exp, b=got, autojunk=False).get_opcodes():
            if op != "equal":
                print(f"   {op}: forgatókönyv {''.join(exp[i1:i2]) or '-'} (#{i1 + 1}) ↔ referencia "
                      f"{''.join(got[j1:j2]) or '-'} (#{j1 + 1})")
        print("   (eltérés: kimaradt / beszúrt mondat a felolvasásban, vagy annotálási hiba — nézd meg)")

    stats = ref_stats(ref_segs)
    print(f"Referencia: {stats['segments']} szakasz, beszéd {stats['speech_sec']} s, ebből átfedés "
          f"{stats['overlap_sec']} s; beszélőnként {stats['per_speaker_sec']}; felvétel {duration:.1f} s")

    # 2) a rendszer-kimenet (megszólalások) kétféle beszélővel
    ref = annotation(ref_segs, ROLE)
    results = {}
    for field, name in (("asr_speaker", "diarizáció + hanglenyomat (átnézés előtt)"),
                        ("speaker", "végleges (AI-átnézés után)")):
        h = annotation([(x["start_ms"] / 1000, x["end_ms"] / 1000, x[field]) for x in hyp])
        results[field] = {"name": name, "metrics": metrics(ref, h, uem), "utterances": utterance_check(hyp, ref_segs, field)}
    changed = sum(1 for x in hyp if x["speaker"] != x["asr_speaker"])

    # 3) opcionálisan a nyers pyannote-kimenet
    raw = None
    if a.nyers:
        src = audio_file(meta, a.audio)
        full, excl = raw_diarization(src, a.num_speakers)
        raw = {"overlap_aware": metrics(ref, full, uem), "exclusive": metrics(ref, excl, uem),
               "speakers": len(full.labels())}

    # 4) jelentés
    def pct(x):
        return f"{100 * x:.1f}%"

    print(f"\n### DER — felvétel {a.recording} ({meta['mode']}), {len(hyp)} rendszer-megszólalás, "
          f"ebből {changed} beszélőjét az AI-átnézés módosította\n")
    print("| Kimenet | Collar | Átfedés | DER | hiány | téves riasztás | összetévesztés | szerepkiosztási hiba |")
    print("|---|---|---|---|---|---|---|---|")
    rows = [(r["name"], r["metrics"]) for r in results.values()]
    if raw:
        rows += [("pyannote nyers, átfedéssel", raw["overlap_aware"]), ("pyannote nyers, kizárólagos", raw["exclusive"])]
    for name, m in rows:
        for key, v in m.items():
            col, ov = key.split("_", 1)
            t = v["total"] or 1
            print(f"| {name} | ±{col[6:]} s | {'van' if ov == 'atfedessel' else 'kizárva'} | {pct(v['der'])} | "
                  f"{pct(v['missed'] / t)} | {pct(v['false_alarm'] / t)} | {pct(v['confusion'] / t)} | "
                  f"{pct(v['role_err']) if not name.startswith('pyannote') else '–'} |")
    print("\n(DER: optimális címke-párosítással; szerepkiosztási hiba: rögzített O = DOCTOR, B = PATIENT párosítással —"
          " a nyers pyannote-kimenetnek nincs szerepe, ott csak DER. Az arányok a referencia-beszédidőhöz mérve.)")

    print("\nMegszólalásonként (a rendszer megszólalása ↔ az időben legtöbbet átfedő referencia-beszélő):\n")
    print("| Kimenet | Megszólalás | Helyes szerep | Rossz szerep | Referencia-beszéd nélkül | Pontosság |")
    print("|---|---|---|---|---|---|")
    for r in results.values():
        u = r["utterances"]
        print(f"| {r['name']} | {u['n']} | {u['correct']} | {u['wrong']} | {u['no_ref_speech']} | "
              f"{pct(u['accuracy']) if u['accuracy'] is not None else '–'} |")
    if a.verbose:
        for field, r in results.items():
            print(f"\n== Rossz szerepű megszólalások ({r['name']}):")
            for row in r["utterances"]["_rows"]:
                print(f"   {row}")

    for r in results.values():
        r["utterances"]["wrong_rows"] = r["utterances"].pop("_rows")
    p = save_json(f"der_rec{a.recording}_{datetime.now():%Y%m%d_%H%M%S}.json",
                  {"recording": a.recording, "mode": meta["mode"], "duration_sec": duration, "ref_file": Path(a.ref).name,
                   "script_check": seq_info, "ref_stats": stats, "speaker_changed_by_review": changed,
                   "system": results, "pyannote_raw": raw})
    print(f"\nNyers eredmény: {p}")


# --------------------------------------------------------------------------- szöveg-alapú beszélő-hozzárendelés
def script_words(script) -> list[dict]:
    """A forgatókönyv szavai sorrendben, a kimondó szereplővel; az átfedő betét szavai a jelölés helyén, `ov` jelzéssel."""
    import wer
    out = []
    for t in script:
        for part in re.split(r"(‹[OB]:.*?›)", t["raw"]):
            m = re.match(r"‹([OB]):\s*(.*?)›", part)
            spk, text, ov = (m.group(1), m.group(2), True) if m else (t["speaker"], part, False)
            for w in wer.tokens_fold(text):
                out.append({"w": w, "spk": ROLE[spk], "turn": t["id"], "ov": ov})
    return out


def system_words(hyp) -> list[dict]:
    import wer
    out = []
    for h in hyp:
        for w in wer.tokens_fold(h["text"] or ""):
            out.append({"w": w, "speaker": h["speaker"], "asr_speaker": h["asr_speaker"], "turn_id": h["turn_id"]})
    return out


def align_pairs(ref: list[str], hyp: list[str]) -> list[tuple]:
    """Levenshtein-illesztés párjai: (i, j) egyezés / helyettesítés, (i, None) kimaradt, (None, j) többlet szó."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        ri, row, prev = ref[i - 1], d[i], d[i - 1]
        for j in range(1, m + 1):
            row[j] = min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (ri != hyp[j - 1]))
    i, j, pairs = n, m, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            pairs.append((i - 1, None))
            i -= 1
        else:
            pairs.append((None, j - 1))
            j -= 1
    return list(reversed(pairs))


def attribution(script, ref, sysw, matched, hyp, field: str) -> dict:
    f = {}
    for name, sel in (("osszes", lambda r: True), ("fo_szoveg", lambda r: not r["ov"]), ("atfedes", lambda r: r["ov"]),
                      ("orvos", lambda r: r["spk"] == "DOCTOR" and not r["ov"]),
                      ("beteg", lambda r: r["spk"] == "PATIENT" and not r["ov"])):
        pr = [(r, s) for r, s in matched if sel(r)]
        ok = sum(1 for r, s in pr if r["spk"] == s[field])
        f[name] = {"n": len(pr), "ok": ok, "acc": round(ok / len(pr), 4) if pr else None}
    # forgatókönyv-sor: a sor (átfedő betét nélküli) illesztett szavainak többsége a jó szereplőhöz került-e
    turns = {}
    for r, s in matched:
        if not r["ov"]:
            turns.setdefault(r["turn"], []).append(r["spk"] == s[field])
    wrong = [t for t, v in turns.items() if sum(v) * 2 <= len(v)]
    f["forgatokonyv_sor"] = {"n": len(turns), "ok": len(turns) - len(wrong),
                             "acc": round(1 - len(wrong) / len(turns), 4) if turns else None,
                             "missing": len(script) - len(turns), "wrong": wrong}
    # rendszer-megszólalás: illesztett szavainak többsége a saját szerepéé-e; „vegyes” = két szereplő szavai egyben
    utt = {}
    for r, s in matched:
        utt.setdefault(s["turn_id"], []).append(r["spk"] == s[field])
    ok_u = sum(1 for v in utt.values() if sum(v) * 2 > len(v))
    f["rendszer_megszolalas"] = {"n": len(utt), "ok": ok_u, "acc": round(ok_u / len(utt), 4) if utt else None,
                                 "vegyes": sum(1 for v in utt.values() if 0 < sum(v) < len(v))}
    return f


def cmd_text(a) -> None:
    """Beszélő-hozzárendelési pontosság: a forgatókönyv (ki mit mond) ↔ a rendszer beszélőnként bontott átirata."""
    try:
        import num2words  # noqa: F401
    except ImportError:
        sys.exit("Hiányzik a num2words (a számokat szavakká alakítja, mint a wer.py-ban): pip install num2words")
    meta = recording_meta(a.recording)
    hyp = hyp_segments(a.recording)
    if not hyp:
        sys.exit("A felvételnek nincsenek megszólalásai.")
    script = load_script()
    ref, sysw = script_words(script), system_words(hyp)
    pairs = align_pairs([r["w"] for r in ref], [s["w"] for s in sysw])
    matched = [(ref[i], sysw[j]) for i, j in pairs if i is not None and j is not None]
    exact = sum(1 for r, s in matched if r["w"] == s["w"])
    dels = sum(1 for i, j in pairs if j is None)
    ins = sum(1 for i, j in pairs if i is None)
    res = {"recording": a.recording, "mode": meta["mode"], "ref_words": len(ref), "sys_words": len(sysw),
           "aligned": len(matched), "aligned_exact": exact, "deleted": dels, "inserted": ins,
           "wer_fold": round((len(matched) - exact + dels + ins) / len(ref), 4),
           "speaker_changed_by_review": sum(1 for h in hyp if h["speaker"] != h["asr_speaker"])}
    for field in ("asr_speaker", "speaker"):
        res[field] = attribution(script, ref, sysw, matched, hyp, field)

    def pct(x):
        return "–" if x["acc"] is None else f"{100 * x['acc']:.1f}% ({x['ok']}/{x['n']})"

    print(f"### Beszélő-hozzárendelési pontosság — felvétel {a.recording} ({meta['mode']})\n")
    print(f"Forgatókönyv {len(ref)} szó, átirat {len(sysw)} szó; illesztett {len(matched)} (ebből pontos egyezés {exact}), "
          f"kimaradt {dels}, többlet {ins}; írásmód-független WER {100 * res['wer_fold']:.1f}%. "
          f"A pontosság csak az illesztett szavakon számol (a kimaradt / többlet szónak nincs párja).\n")
    print("| Szint | Átnézés előtt (diarizáció + hanglenyomat) | Végleges (AI-átnézés után) |")
    print("|---|---|---|")
    for key, label in (("osszes", "szó — összes"), ("fo_szoveg", "szó — átfedő betétek nélkül"), ("orvos", "szó — orvos"),
                       ("beteg", "szó — beteg"), ("atfedes", "szó — csak az átfedő betétek"),
                       ("forgatokonyv_sor", "forgatókönyv-sor (többségi)"),
                       ("rendszer_megszolalas", "rendszer-megszólalás (többségi)")):
        print(f"| {label} | {pct(res['asr_speaker'][key])} | {pct(res['speaker'][key])} |")
    fs = res["speaker"]["forgatokonyv_sor"]
    print(f"\nAz AI-átnézés {res['speaker_changed_by_review']} megszólalás beszélőjét módosította. Vegyes (két szereplő "
          f"szavait egyben tartalmazó) rendszer-megszólalás: {res['speaker']['rendszer_megszolalas']['vegyes']}. "
          f"Átirat nélküli forgatókönyv-sor: {fs['missing']}.")
    if fs["wrong"]:
        print(f"Rossz szereplőhöz került sorok (végleges): {', '.join(fs['wrong'])}")
    if a.verbose:
        print("\n== Rossz szereplőhöz rendelt szavak (végleges), forgatókönyv-soronként:", end="")
        cur = None
        for r, s in matched:
            if r["spk"] != s["speaker"]:
                if r["turn"] != cur:
                    cur = r["turn"]
                    print(f"\n   {cur}{' (átfedő betét)' if r['ov'] else ''} → rendszer #{s['turn_id']} {s['speaker']}:", end="")
                print(f" {s['w']}", end="")
        print()
    p = save_json(f"beszelo_rec{a.recording}_{datetime.now():%Y%m%d_%H%M%S}.json", res)
    print(f"\nNyers eredmény: {p}")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("elokeszit", help="hang wav-ba + annotálási váz + rendszer-kimenet címkesávként")
    p1.add_argument("--recording", type=int, required=True)
    p1.add_argument("--audio", help="a hangfájl útja, ha nem a felvétel audio_path-ja")
    p0 = sub.add_parser("lista", help="a tesztbeteg felvételei (id, mód, állapot)")
    p0.add_argument("--patient", type=int, required=True)
    p3 = sub.add_parser("szoveg", help="beszélő-hozzárendelési pontosság a forgatókönyv szövegéből (annotálás nélkül)")
    p3.add_argument("--recording", type=int, required=True)
    p3.add_argument("--verbose", action="store_true")
    p2 = sub.add_parser("pontoz", help="DER és szerepkiosztási hiba a kézi referenciával")
    p2.add_argument("--recording", type=int, required=True)
    p2.add_argument("--ref", required=True, help="a kézi annotáció (Audacity-címkék, O / B)")
    p2.add_argument("--nyers", action="store_true", help="a nyers pyannote-kimenet DER-je is (GPU ajánlott)")
    p2.add_argument("--num-speakers", type=int, default=2)
    p2.add_argument("--audio")
    p2.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    {"lista": cmd_list, "elokeszit": cmd_prepare, "pontoz": cmd_score, "szoveg": cmd_text}[a.cmd](a)


if __name__ == "__main__":
    main()
