"""F) WER — a felolvasott referenciaszövegek (wer_szovegek.md, W1–W5) és a felvételek átirata.

Megszólalások sorrendjében (turn_id) összefűzve kétféle szöveget mér:
  - ASR:      az AI-javítás ELŐTTI szöveg (utterance.asr_text, ha a javítás átírta; különben utterance.text)
  - javított: az átnézés utáni szöveg (utterance.text)
Kétféle WER: nyers (kisbetű, írásjelek nélkül) és normalizált (a számok mindkét oldalon szavakkal, num2words
lang='hu', a tizedesvesszővel is; % → százalék, / → per). A kimenetben csak szám van (a szöveg nem, csak
--verbose-szal az illesztés).

Telepítés egyszer:  pip install num2words   (abba a Pythonba, amivel futtatod)
Használat (a repó gyökeréből, a Postgres-konténer fut):
  python tools\\meresek_2026-11\\wer.py --patient 25                 # a beteg felvételei felvételi sorrendben → W1..W5
  python tools\\meresek_2026-11\\wer.py --map 31=W1 32=W2 33=W3      # felvétel-id = referencia
  python tools\\meresek_2026-11\\wer.py --patient 25 --verbose       # + szóillesztés (hibák) felvételenként
"""
import argparse
import json
import re
import sys
import unicodedata
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import HERE, save_json, sql  # noqa: E402

REFS = HERE / "wer_szovegek.md"
KEYS = ("asr_raw", "asr_norm", "corr_raw", "corr_norm", "asr_fold", "corr_fold", "asr_cer", "corr_cer")
LETTERS = "a-záéíóöőúüű"


def load_refs() -> dict[str, str]:
    """`## W1 …` fejlécek alatti szöveg (a következő `##`-ig); a `>` / `<!-- -->` sorok kimaradnak."""
    text = REFS.read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    refs, key = {}, None
    for line in text.splitlines():
        m = re.match(r"^##\s*(W\d+)\b", line.strip())
        if m:
            key = m.group(1)
            refs[key] = []
        elif key and not line.lstrip().startswith(">"):
            refs[key].append(line)
    return {k: " ".join(v).strip() for k, v in refs.items() if " ".join(v).strip()}


def tokens_raw(s: str) -> list[str]:
    s = s.lower().replace(" ", " ")
    # tizedesvessző/-pont számok között maradjon a szám része, egyébként minden írásjel szóköz
    s = re.sub(r"(?<=\d)[.,](?=\d)", "\x00", s)
    s = re.sub(rf"[^0-9{LETTERS}\x00]+", " ", s)
    return [t.replace("\x00", ",") for t in s.split()]


def _num_words(tok: str) -> str:
    from num2words import num2words
    try:
        return num2words(Decimal(tok.replace(",", ".")), lang="hu")
    except (InvalidOperation, NotImplementedError, OverflowError):
        return tok


def tokens_norm(s: str) -> list[str]:
    s = s.lower().replace("%", " százalék ").replace("/", " per ")
    out = []
    for t in tokens_raw(s):
        if re.fullmatch(r"\d+(,\d+)?", t):
            out += tokens_raw(_num_words(t))
        else:
            # számjegy betűvel összeírva (pl. „5mg”): szétválasztva
            parts = re.findall(rf"\d+(?:,\d+)?|[{LETTERS}]+", t)
            for p in parts:
                out += tokens_raw(_num_words(p)) if p[0].isdigit() else [p]
    return out


def fold(tok: str) -> str:
    """Írásmód-független alak: a latinos / magyaros írás (y–i, c–k, ph–f, th–t, ae–e, sch–szk), az ékezet és a
    kettőzött betű nem számít. Csak összevetéshez (ugyanaz mindkét oldalon), nem helyes alak."""
    t = "".join(c for c in unicodedata.normalize("NFKD", tok) if not unicodedata.combining(c))
    for a, b in (("sch", "szk"), ("ph", "f"), ("th", "t"), ("ae", "e"), ("oe", "e"), ("cs", "\x01"),
                 ("c", "k"), ("\x01", "cs"), ("sz", "s")):
        t = t.replace(a, b)
    t = re.sub(r"(?<![gntl])y", "i", t)  # a magyar gy / ny / ty / ly marad
    return re.sub(r"(.)\1+", r"\1", t)


def tokens_fold(s: str) -> list[str]:
    return [fold(t) for t in tokens_norm(s)]


def cer(ref: str, hyp: str) -> dict:
    """Karakterhiba-arány az írásmód-független, szóköz nélküli szövegen (az egybe- / különírás sem számít)."""
    r, h = list("".join(tokens_fold(ref))), list("".join(tokens_fold(hyp)))
    S, D, I, _ = align(r, h)
    return {"N": len(r), "S": S, "D": D, "I": I, "wer": round((S + D + I) / len(r), 4) if r else None, "_ops": []}


def align(ref: list[str], hyp: list[str]):
    """Levenshtein szinten: (helyettesítés, törlés, beszúrás, illesztés-lista)."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]))
    i, j, S, D, I, ops = n, m, 0, 0, 0, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            if ref[i - 1] != hyp[j - 1]:
                S += 1
                ops.append(f"~ {ref[i - 1]} → {hyp[j - 1]}")
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            D += 1
            ops.append(f"- {ref[i - 1]}")
            i -= 1
        else:
            I += 1
            ops.append(f"+ {hyp[j - 1]}")
            j -= 1
    return S, D, I, list(reversed(ops))


def wer(ref: list[str], hyp: list[str]) -> dict:
    S, D, I, ops = align(ref, hyp)
    return {"N": len(ref), "S": S, "D": D, "I": I, "wer": round((S + D + I) / len(ref), 4) if ref else None,
            "_ops": ops}


def recording_texts(rid: int) -> tuple[str, str]:
    rows = sql(f"select turn_id, text, asr_text from utterance where recording_id = {rid} order by turn_id")
    return (" ".join(r["asr_text"] or r["text"] for r in rows), " ".join(r["text"] for r in rows))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--patient", type=int, help="a tesztbeteg id-je: DONE felvételei felvételi sorrendben → W1, W2, …")
    ap.add_argument("--map", nargs="*", default=[], help="felvétel-id=W-kulcs párok, pl. 31=W1")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    try:
        import num2words  # noqa: F401
    except ImportError:
        sys.exit("Hiányzik a num2words: pip install num2words")
    refs = load_refs()
    if not refs:
        sys.exit(f"Nincs referenciaszöveg a {REFS.name}-ben (## W1 … fejlécek alatt).")
    pairs = [(int(x.split("=")[0]), x.split("=")[1].upper()) for x in a.map]
    if a.patient:
        recs = sql(f"select id, mode, status from recording where patient_id = {a.patient} and status = 'DONE' "
                   f"order by recorded_at, id")
        pairs += [(r["id"], k) for r, k in zip(recs, sorted(refs, key=lambda k: int(k[1:])))]
    if not pairs:
        sys.exit("Adj meg --patient-et vagy --map-et.")

    rows, tot = [], {}
    for rid, key in pairs:
        if key not in refs:
            print(f"{rid}: nincs {key} referencia, kihagyva")
            continue
        asr, corr = recording_texts(rid)
        if not asr:
            print(f"{rid}: nincs megszólalás, kihagyva")
            continue
        ref = refs[key]
        res = {"recording": rid, "ref": key,
               "asr_raw": wer(tokens_raw(ref), tokens_raw(asr)), "asr_norm": wer(tokens_norm(ref), tokens_norm(asr)),
               "corr_raw": wer(tokens_raw(ref), tokens_raw(corr)), "corr_norm": wer(tokens_norm(ref), tokens_norm(corr)),
               "asr_fold": wer(tokens_fold(ref), tokens_fold(asr)), "corr_fold": wer(tokens_fold(ref), tokens_fold(corr)),
               "asr_cer": cer(ref, asr), "corr_cer": cer(ref, corr)}
        rows.append(res)
        for k in KEYS:
            t = tot.setdefault(k, {"N": 0, "E": 0})
            t["N"] += res[k]["N"]
            t["E"] += res[k]["S"] + res[k]["D"] + res[k]["I"]
        if a.verbose:
            print(f"\n== felvétel {rid} ({key}) — ASR, normalizált:")
            print("\n".join("   " + o for o in res["asr_norm"]["_ops"]) or "   (nincs hiba)")
            print(f"== felvétel {rid} ({key}) — javított, normalizált:")
            print("\n".join("   " + o for o in res["corr_norm"]["_ops"]) or "   (nincs hiba)")
            print(f"== felvétel {rid} ({key}) — ASR, írásmód-független (a valódi félreismerések):")
            print("\n".join("   " + o for o in res["asr_fold"]["_ops"]) or "   (nincs hiba)")

    print("\n| Felvétel | Ref. | Szó (ref.) | WER ASR nyers | WER ASR normalizált | WER javított nyers | WER javított normalizált |")
    print("|---|---|---|---|---|---|---|")
    f = lambda x: f"{100 * x['wer']:.1f}% ({x['S']}/{x['D']}/{x['I']})"
    for r in rows:
        print(f"| {r['recording']} | {r['ref']} | {r['asr_raw']['N']} | {f(r['asr_raw'])} | {f(r['asr_norm'])} | "
              f"{f(r['corr_raw'])} | {f(r['corr_norm'])} |")
    if tot:
        g = lambda k: f"{100 * tot[k]['E'] / tot[k]['N']:.1f}%"
        print(f"| **összesen** | | {tot['asr_raw']['N']} | {g('asr_raw')} | {g('asr_norm')} | {g('corr_raw')} | {g('corr_norm')} |")
        print("\n(zárójelben: helyettesítés / törlés / beszúrás; a WER a súlyozott összeg / referencia-szavak száma)")
        print("\nÍrásmód-független (y–i, c–k, sz–s, ph–f, ae–e, ékezet, kettőzött betű nem számít; CER: szóköz nélkül, "
              "így az egybe- / különírás sem):\n")
        print("| Felvétel | Ref. | WER ASR | WER javított | CER ASR | CER javított |")
        print("|---|---|---|---|---|---|")
        for r in rows:
            print(f"| {r['recording']} | {r['ref']} | {f(r['asr_fold'])} | {f(r['corr_fold'])} | "
                  f"{100 * r['asr_cer']['wer']:.1f}% | {100 * r['corr_cer']['wer']:.1f}% |")
        print(f"| **összesen** | | {g('asr_fold')} | {g('corr_fold')} | {g('asr_cer')} | {g('corr_cer')} |")
    for r in rows:
        for k in KEYS:
            r[k].pop("_ops", None)
    save_json(f"wer_{datetime.now():%Y%m%d_%H%M%S}.json", {"rows": rows, "total": tot})


if __name__ == "__main__":
    main()
