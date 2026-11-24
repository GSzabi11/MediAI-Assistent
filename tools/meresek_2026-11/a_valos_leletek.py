"""A) Valós leleteken végzett próba — a DB-ben lévő összes dokumentum összesítése.

Csak összesített számot ír ki (eredmenyek/a_valos_leletek.json). A kézi ellenőrzéshez 30 véletlen laborsort
(seed=20261115) a kezi_ellenorzes.csv-be ír — ez tartalmaz leletadatot, a gépen marad (gitignore).

Futtatás (a repó gyökeréből):  python tools\\meresek_2026-11\\a_valos_leletek.py
"""
import csv
import random
import re
import unicodedata
from decimal import Decimal

from common import HERE, save_json, sql, stats

SEED = 20261115


def norm(s: str) -> str:
    """Kisbetű, NFC, egységes szóköz, tizedesvessző → pont (számok között)."""
    s = unicodedata.normalize("NFC", s or "").lower()
    s = re.sub(r"(?<=\d),(?=\d)", ".", s)
    s = s.replace(" ", " ")
    return re.sub(r"\s+", " ", s)


def num_pattern(v: str, comparator: str | None) -> str:
    d = Decimal(v).normalize()
    s = format(d, "f")
    core = re.escape(s) + ("0*" if "." in s else r"(?:\.0*)?")
    if s.startswith("0."):            # „,5” / „.5” írásmód is
        core = "0?" + core
    pre = ""
    if comparator:
        pre = re.escape(comparator).replace("<=", "(?:<=|≤)").replace(">=", "(?:>=|≥)") + r"\s*"
    return r"(?<![\d.])" + pre + core + r"(?!\d)"


def value_found(text: str, row: dict) -> bool:
    if row["value_text"] is not None:
        return norm(row["value_text"]).strip() in text
    return re.search(num_pattern(str(row["value_num"]), row["comparator"]), text) is not None


def name_found(text: str, name: str) -> bool:
    n = norm(name).strip()
    if n in text:
        return True
    # az akkreditációs „(A)” jelölés nélkül is
    n2 = re.sub(r"\s*\(a\)\s*$", "", n).strip()
    return bool(n2) and n2 in text


def name_found_relaxed(text: str, name: str) -> bool:
    """A kinyerés néha minősítőt fűz a névhez („Fehérje (vizelet)”): a záró zárójeles tagok nélkül is keressük."""
    if name_found(text, name):
        return True
    base = norm(name).strip()
    while re.search(r"\s*\([^()]*\)?\s*$", base) and base != re.sub(r"\s*\([^()]*\)?\s*$", "", base):
        base = re.sub(r"\s*\([^()]*\)?\s*$", "", base).strip()
        if len(base) >= 2 and base in text:
            return True
    return False


def near(text: str, row: dict, window: int = 250) -> bool:
    """A név után legfeljebb `window` karakteren belül ott az érték (ugyanaz a sor / blokk)."""
    n = norm(row["name"]).strip()
    n2 = re.sub(r"\s*\(a\)\s*$", "", n).strip()
    pat = (r"(?<![\d.])" + re.escape(norm(row["value_text"]).strip())) if row["value_text"] is not None \
        else num_pattern(str(row["value_num"]), row["comparator"])
    for cand in {n, n2}:
        if not cand:
            continue
        for m in re.finditer(re.escape(cand), text):
            if re.search(pat, text[m.end():m.end() + window]):
                return True
    return False


def main():
    docs = sql("""select id, patient_id, sha256, type, mime_type, status, size_bytes,
                         extract(epoch from processed_at - uploaded_at) as sec, extraction_model,
                         extracted_text is not null as has_text
                  from document""")
    by_type = {}
    for d in docs:
        by_type.setdefault(d["type"] or "(nincs)", {}).setdefault(d["status"], 0)
        by_type[d["type"] or "(nincs)"][d["status"]] += 1
    uniq = {}
    for d in docs:
        uniq.setdefault(d["sha256"], []).append(d)
    uniq_type = {}
    for sha, ds in uniq.items():
        t = next((d["type"] for d in ds if d["type"]), "(nincs)")
        uniq_type[t] = uniq_type.get(t, 0) + 1

    status = {}
    for d in docs:
        status[d["status"]] = status.get(d["status"], 0) + 1
    times = [d["sec"] for d in docs if d["sec"] is not None and d["status"] == "PROCESSED"]
    models = {}
    for d in docs:
        models[d["extraction_model"] or "(nincs)"] = models.get(d["extraction_model"] or "(nincs)", 0) + 1

    labs = sql("""select l.id, l.document_id, l.name, l.value_num::text as value_num, l.value_text, l.comparator,
                         l.unit, l.ref_low::text as ref_low, l.ref_high::text as ref_high, l.ref_text,
                         l.source_page is not null as located
                  from lab_result l""")
    texts = {r["id"]: norm(r["t"]) for r in sql("select id, extracted_text as t from document where extracted_text is not null")}
    med = sql("select origin, count(*) as n from medication group by origin")
    alg = sql("select origin, count(*) as n from allergy group by origin")

    chk = {"rows": 0, "with_doc": 0, "doc_has_text": 0, "name": 0, "value": 0, "both": 0, "near": 0, "name_relaxed": 0, "both_relaxed": 0,
           "kinds": {"numeric": [0, 0], "censored": [0, 0], "text": [0, 0]}}
    for r in labs:
        chk["rows"] += 1
        if r["document_id"] is None:
            continue
        chk["with_doc"] += 1
        t = texts.get(r["document_id"])
        if t is None:
            continue
        chk["doc_has_text"] += 1
        nf, vf = name_found(t, r["name"]), value_found(t, r)
        nr = nf and vf and near(t, r)
        chk["name"] += nf
        chk["value"] += vf
        chk["both"] += nf and vf
        chk["near"] += nr
        nrx = name_found_relaxed(t, r["name"])
        chk["name_relaxed"] += nrx
        chk["both_relaxed"] += nrx and vf
        kind = "text" if r["value_text"] is not None else ("censored" if r["comparator"] else "numeric")
        chk["kinds"][kind][0] += 1
        chk["kinds"][kind][1] += nf and vf

    # ugyanannak a fájlnak a többszöri feldolgozása (más-más betegnél) — ugyanannyi laborsor jött-e ki?
    per_doc = {r["document_id"]: 0 for r in labs if r["document_id"]}
    for r in labs:
        if r["document_id"]:
            per_doc[r["document_id"]] += 1
    rep = {"files_multi": 0, "files_same_count": 0}
    for sha, ds in uniq.items():
        lab_docs = [d for d in ds if d["status"] == "PROCESSED" and d["type"] == "LAB"]
        if len(lab_docs) >= 2:
            rep["files_multi"] += 1
            counts = {per_doc.get(d["id"], 0) for d in lab_docs}
            rep["files_same_count"] += len(counts) == 1

    # ugyanazon kinyerő-verzión (extractor_version) belül azonos-e a laborsorok száma
    vers = sql("""select d.sha256, d.extractor_version as v, (select count(*) from lab_result l where l.document_id = d.id) as n
                  from document d where d.type = 'LAB' and d.status = 'PROCESSED'""")
    groups = {}
    for x in vers:
        groups.setdefault((x["sha256"], x["v"]), []).append(x["n"])
    multi = [g for g in groups.values() if len(g) >= 2]
    rep["same_version_groups"] = len(multi)
    rep["same_version_identical"] = sum(1 for g in multi if len(set(g)) == 1)
    rep["same_version_runs"] = sum(len(g) for g in multi)

    rule = sql("""select producer->>'ruleId' as rule, severity, count(*) as n from statement
                  where type = 'DETERMINISTIC_RULE' group by 1, 2 order by 1, 2""")

    result = {
        "documents": {"rows": len(docs), "unique_files": len(uniq), "patients": len({d["patient_id"] for d in docs}),
                      "by_type_status": by_type, "unique_by_type": uniq_type, "status": status,
                      "mime": {m: sum(1 for d in docs if d["mime_type"] == m) for m in {d["mime_type"] for d in docs}},
                      "extraction_model": models,
                      "processing_seconds_upload_to_processed": stats(times)},
        "lab_rows": {"total": len(labs), "with_document": chk["with_doc"],
                     "located_in_pdf": sum(1 for r in labs if r["located"]),
                     "numeric": sum(1 for r in labs if r["value_text"] is None and not r["comparator"]),
                     "censored": sum(1 for r in labs if r["comparator"]),
                     "text": sum(1 for r in labs if r["value_text"] is not None)},
        "verbatim_check": chk,
        "repeat_processing": rep,
        "medications": med, "allergies": alg,
        "rule_statements": rule,
    }
    save_json("a_valos_leletek.json", result)

    # 30 véletlen laborsor kézi ellenőrzésre (rögzített seed)
    rnd = random.Random(SEED)
    sample = rnd.sample(sorted(labs, key=lambda r: r["id"]), min(30, len(labs)))
    with open(HERE / "kezi_ellenorzes.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["dokumentum_id", "sor_neve", "ertek", "mertekegyseg", "referencia", "helyes?"])
        for r in sorted(sample, key=lambda r: (r["document_id"] or 0, r["id"])):
            val = r["value_text"] if r["value_text"] is not None else \
                (r["comparator"] or "") + format(Decimal(r["value_num"]).normalize(), "f").replace(".", ",")
            ref = r["ref_text"] or "–".join(format(Decimal(x).normalize(), "f").replace(".", ",") if x else ""
                                            for x in (r["ref_low"], r["ref_high"]))
            w.writerow([r["document_id"], r["name"], val, r["unit"] or "", ref if ref != "–" else "", ""])

    import json
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
