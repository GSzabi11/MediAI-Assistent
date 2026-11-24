"""E) Tárhely — a meglévő hangfelvételek mérete másodpercenként és a PDF-ek átlagos mérete, éves becslés.

Feltevés: 100 orvos × 10 beteg/nap × (1 db 5 perces felvétel + 2 PDF) × 250 munkanap.
Futtatás:  python tools\\meresek_2026-11\\e_tarhely.py
"""
from common import ENV, ROOT, save_json, sql, stats

DOCS = ROOT / "backend" / ENV.get("MEDIAI_DOCUMENTS_DIR", "data/documents")


def main():
    recs = sql("""select id, audio_path, duration_sec from recording
                  where audio_path like '%hangfelvetelek%' and audio_deleted_at is null and duration_sec > 0""")
    per_sec, used = [], []
    for r in recs:
        p = DOCS / r["audio_path"]
        if p.exists():
            size = p.stat().st_size
            per_sec.append(size / r["duration_sec"])
            used.append({"bytes": size, "sec": r["duration_sec"], "ext": p.suffix})
    pdf = sql("select size_bytes, sha256 from document where mime_type = 'application/pdf'")
    uniq = {d["sha256"]: d["size_bytes"] for d in pdf}
    pdf_mean_unique = sum(uniq.values()) / len(uniq)

    total_bytes = sum(u["bytes"] for u in used)
    total_sec = sum(u["sec"] for u in used)
    bps = total_bytes / total_sec            # súlyozott átlag (össz-bájt / össz-mp)
    visits = 100 * 10 * 250
    audio_year = visits * 300 * bps
    pdf_year = visits * 2 * pdf_mean_unique
    res = {
        "recordings_measured": len(used), "recordings_total_sec": total_sec,
        "audio_formats": sorted({u["ext"] for u in used}),
        "audio_bytes_per_sec_weighted": round(bps, 1),
        "audio_bytes_per_sec_per_file": stats(per_sec),
        "pdf_rows": len(pdf), "pdf_unique": len(uniq),
        "pdf_mean_bytes_unique": round(pdf_mean_unique), "pdf_size_unique": stats(list(uniq.values())),
        "assumption": "100 orvos × 10 beteg/nap × 250 nap = 250 000 ellátás/év; ellátásonként 300 mp hang + 2 PDF",
        "audio_per_visit_MB": round(300 * bps / 1e6, 2),
        "audio_year_GB": round(audio_year / 1e9, 1), "pdf_year_GB": round(pdf_year / 1e9, 1),
        "total_year_GB": round((audio_year + pdf_year) / 1e9, 1),
    }
    save_json("e_tarhely.json", res)
    import json
    print(json.dumps(res, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
