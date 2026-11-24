"""G) Az orvos terhe — állítások betegenként típus / súlyosság / döntés szerint (csak összesítve).

Futtatás:  python tools\\meresek_2026-11\\g_orvos_terhe.py
"""
from common import save_json, sql, stats

TYPES = ["DETERMINISTIC_RULE", "RETRIEVED_FACT", "MODEL_INFERENCE"]


def main():
    rows = sql("""
        with last as (
          select distinct on (statement_id) statement_id, status
          from statement_approval order by statement_id, decided_at desc, id desc)
        select s.patient_id, s.type, s.severity, coalesce(l.status, 'PENDING') as review,
               s.producer->>'ruleId' as rule, s.source->>'type' as source_type
        from statement s left join last l on l.statement_id = s.id""")
    patients = [p["id"] for p in sql("select id from pacient")]
    per = {p: {} for p in patients}
    for r in rows:
        d = per[r["patient_id"]]
        for k in ("all", r["type"], f"sev:{r['severity']}", f"review:{r['review']}",
                  f"{r['type']}|review:{r['review']}", f"{r['type']}|sev:{r['severity']}"):
            d[k] = d.get(k, 0) + 1
    keys = sorted({k for d in per.values() for k in d})
    with_any = [p for p in patients if per[p].get("all")]
    out = {
        "patients_total": len(patients),
        "patients_with_statements": len(with_any),
        "statements_total": len(rows),
        "totals": {k: sum(d.get(k, 0) for d in per.values()) for k in keys},
        # betegenként, CSAK az állítással rendelkező betegeken
        "per_patient": {k: stats([per[p].get(k, 0) for p in with_any]) for k in keys},
        "by_rule": {},
        "approvals_total": sql("select count(*) as n from statement_approval")[0]["n"],
        "medications": sql("""select m.origin, m.active,
                                     coalesce(l.status, case when m.source_statement_id is null then 'NO_SOURCE' else 'PENDING' end) as review,
                                     count(*) as n
                              from medication m
                              left join lateral (select status from statement_approval a where a.statement_id = m.source_statement_id
                                                 order by decided_at desc, id desc limit 1) l on true
                              group by 1, 2, 3"""),
        "recording_keywords": sql("select status, origin, count(*) as n from recording_keyword group by 1, 2"),
        "recording_findings": sql("""select origin, count(*) filter (where deleted_at is null) as active,
                                            count(*) filter (where edited_at is not null) as edited,
                                            count(*) filter (where deleted_at is not null) as deleted
                                     from recording_finding group by 1"""),
    }
    for r in rows:
        key = r["rule"] or r["type"]
        out["by_rule"].setdefault(key, {}).setdefault(r["review"], 0)
        out["by_rule"][key][r["review"]] += 1
    save_json("g_orvos_terhe.json", out)
    import json
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
