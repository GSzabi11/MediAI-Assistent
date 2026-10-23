"""Szakirodalom-keresés a hivatalos API-kon (docs/literature-research.md). Ez az egyetlen modul, amely
a gépen kívülre hív: a kimenő kérésekben CSAK a keresőkifejezés és a cikkazonosítók (PMID) vannak,
betegadat nincs — a kifejezést a Spring ellenőrzi és az orvos jóváhagyja, mielőtt ide ér.

- PubMed: NCBI E-utilities (esearch → PMID-k, efetch → cím, szerzők, év, absztrakt, DOI).
- Cochrane: a Cochrane Database of Systematic Reviews a PubMedben indexelt — ugyanaz a keresés a
  folyóiratra szűrve. (A cochranelibrary.com saját API-ja kulcshoz kötött.)
- Europe PMC: szabad szavas keresés (kulcs nélkül) — a Google Scholar helyett, amelynek nincs API-ja.
  Csak a PMID-es (PubMedben is meglévő) találatok, a részletek ugyanúgy az efetch-ből.
- Hivatkozási szám: NIH iCite, PMID alapján, kulcs nélkül (az OpenAlex 2026 óta kulcsot és napi
  keretet kér). A Google Scholarnak nincs API-ja, és az ÁSZF tiltja a gépi lekérdezést.
"""
import logging
import re
import time
import xml.etree.ElementTree as ET

import requests

import config

log = logging.getLogger("literature")

EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
ICITE = "https://icite.od.nih.gov/api/pubs"
CITATION_SOURCE = "iCite"
COCHRANE_FILTER = '"Cochrane Database Syst Rev"[Journal]'
EUROPEPMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/searchPOST"
SOURCES = ("PUBMED", "COCHRANE", "EUROPE_PMC")
SOURCE_LABELS = {"PUBMED": "PubMed", "COCHRANE": "Cochrane", "EUROPE_PMC": "Europe PMC"}
_FIELD_TAG = re.compile(r"\[[^\]]*\]")
FETCH_BATCH = 50
ICITE_BATCH = 200
MAX_ABSTRACT_CHARS = 20000
MAX_AUTHORS = 50


class LiteratureError(Exception):
    """A külső forrás hibája, a felhasználónak is értelmes üzenettel."""


_last_ncbi_call = 0.0


def _ncbi(endpoint: str, params: dict) -> requests.Response:
    """E-utilities hívás a kérési korlát betartásával (kulcs nélkül 3/s, kulccsal 10/s)."""
    global _last_ncbi_call
    gap = 0.11 if config.NCBI_API_KEY else 0.35
    wait = _last_ncbi_call + gap - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    params = {**params, "db": "pubmed", "tool": "mediai"}
    if config.NCBI_API_KEY:
        params["api_key"] = config.NCBI_API_KEY
    if config.NCBI_EMAIL:
        params["email"] = config.NCBI_EMAIL
    try:
        # POST: a hosszú PMID-lista sem fér az URL-be; a kifejezés így a proxy-naplókba sem kerül
        resp = requests.post(f"{EUTILS}/{endpoint}", data=params, timeout=config.LITERATURE_TIMEOUT_SEC)
        resp.raise_for_status()
    except requests.RequestException as e:
        raise LiteratureError(f"A PubMed nem érhető el: {e}"[:500]) from e
    finally:
        _last_ncbi_call = time.monotonic()
    return resp


def search_ids(term: str, limit: int) -> tuple[int, list[str]]:
    """(összes találat, az első `limit` PMID relevancia szerint)."""
    data = _ncbi("esearch.fcgi", {"term": term, "retmax": limit, "sort": "relevance", "retmode": "json"}).json()
    result = data.get("esearchresult") or {}
    if "ERROR" in result:
        raise LiteratureError(f"PubMed keresési hiba: {result['ERROR']}"[:500])
    return int(result.get("count") or 0), [i for i in result.get("idlist") or [] if i.isdigit()]


def europepmc_query(query: str) -> str:
    """A PubMed-szintaxisú kifejezés Europe PMC-re: a mezőjelölők ([MeSH], [tiab] …) nélkül (ott mást
    jelentenek), és csak a PubMedben is meglévő (PMID-es) rekordokra — így a cikkadatok és a hivatkozási
    szám ugyanonnan jönnek, mint a többi forrásnál."""
    return f"({' '.join(_FIELD_TAG.sub('', query).split())}) AND SRC:MED"


def europepmc_ids(query: str, limit: int) -> tuple[int, list[str]]:
    """(összes találat, az első `limit` PMID az Europe PMC relevancia-sorrendjében)."""
    try:
        # searchPOST: a kifejezés így nem kerül URL-be / proxy-naplóba
        resp = requests.post(EUROPEPMC, data={"query": europepmc_query(query), "resultType": "idlist",
                                              "format": "json", "pageSize": limit},
                             headers={"User-Agent": "mediai (literature research)"},
                             timeout=config.LITERATURE_TIMEOUT_SEC)
        resp.raise_for_status()
        data = resp.json()
    except (requests.RequestException, ValueError) as e:
        raise LiteratureError(f"Az Europe PMC nem érhető el: {e}"[:500]) from e
    ids = [str(r.get("pmid")) for r in (data.get("resultList") or {}).get("result") or []
           if str(r.get("pmid") or "").isdigit()]
    return int(data.get("hitCount") or 0), ids[:limit]


def search(query: str, sources: list[str], limit: int, progress) -> tuple[int, list[dict]]:
    """A forrásonkénti keresés összefésülve: a Cochrane-áttekintések elöl (ha más forrás is ki van
    választva, legfeljebb a hely felét foglalják), utána a PubMed és az Europe PMC relevancia-sorrendje
    felváltva, PMID szerint egyesítve. Az Europe PMC kiesése nem buktatja el a keresést, ha van más forrás.
    (összes talált, cikkek rangsorolva)."""
    query = query.strip()
    found: dict[str, list[str]] = {}
    totals: dict[str, int] = {}
    notes: list[str] = []
    if "COCHRANE" in sources:
        progress("Cochrane-áttekintések keresése (PubMed, Cochrane Database Syst Rev)…")
        cap = limit // 2 if len(sources) > 1 else limit
        totals["COCHRANE"], found["COCHRANE"] = search_ids(f"({query}) AND {COCHRANE_FILTER}", max(cap, 1))
    if "PUBMED" in sources:
        progress("PubMed keresés…")
        totals["PUBMED"], found["PUBMED"] = search_ids(query, limit)
    if "EUROPE_PMC" in sources:
        progress("Europe PMC keresés…")
        try:
            totals["EUROPE_PMC"], found["EUROPE_PMC"] = europepmc_ids(query, limit)
        except LiteratureError as e:
            if len(sources) == 1:
                raise
            log.warning("%s — a többi forrással folytatjuk", e)
            notes.append("Europe PMC nem érhető el")

    order: list[str] = []
    origin: dict[str, list[str]] = {}

    def add(pmid: str, source: str) -> None:
        if source not in origin.setdefault(pmid, []):
            origin[pmid].append(source)
        if pmid not in order:
            order.append(pmid)

    for pmid in found.get("COCHRANE", []):
        add(pmid, "COCHRANE")
    pubmed, epmc = found.get("PUBMED", []), found.get("EUROPE_PMC", [])
    for i in range(max(len(pubmed), len(epmc))):
        for source, ids in (("PUBMED", pubmed), ("EUROPE_PMC", epmc)):
            if i < len(ids):
                add(ids[i], source)
    order = order[:limit]
    # a források átfednek (a Cochrane a PubMed része): az összes a legnagyobb forrásé
    total = max(totals.values(), default=0)
    summary = ", ".join([f"{SOURCE_LABELS[s]}: {n} találat" for s, n in totals.items()] + notes)
    if not order:
        progress(f"{summary} — nincs letölthető cikk")
        return total, []

    articles: list[dict] = []
    for i in range(0, len(order), FETCH_BATCH):
        batch = order[i:i + FETCH_BATCH]
        progress(f"{summary}; cikkadatok letöltése ({min(i + len(batch), len(order))}/{len(order)})…")
        articles.extend(fetch_details(batch))
    by_pmid = {a["pmid"]: a for a in articles}

    progress(f"{summary}; hivatkozási számok lekérdezése (iCite)…")
    citations = citation_counts(list(by_pmid))
    out = []
    for pmid in order:
        a = by_pmid.get(pmid)
        if a is None:
            continue  # visszavont / nem letölthető rekord
        a["sources"] = origin[pmid]
        a["citationCount"] = citations.get(pmid)
        a["rank"] = len(out) + 1
        out.append(a)
    return total, out


def _text(el) -> str:
    """Az elem teljes szövege a beágyazott jelölésekkel (<i>, <sup>) együtt, szóközök normalizálva."""
    return " ".join("".join(el.itertext()).split()) if el is not None else ""


def _year(article) -> int | None:
    for path in ("Journal/JournalIssue/PubDate/Year", "ArticleDate/Year"):
        el = article.find(path)
        if el is not None and (el.text or "").strip().isdigit():
            return int(el.text.strip())
    medline = article.find("Journal/JournalIssue/PubDate/MedlineDate")
    head = (medline.text or "").strip()[:4] if medline is not None else ""
    return int(head) if head.isdigit() else None


def _abstract(article) -> str | None:
    parts = []
    for el in article.findall("Abstract/AbstractText"):
        text = _text(el)
        if not text:
            continue
        label = el.get("Label")
        parts.append(f"{label}: {text}" if label else text)
    joined = "\n".join(parts)
    return joined[:MAX_ABSTRACT_CHARS] if joined else None


def _authors(article) -> list[str]:
    out = []
    for el in article.findall("AuthorList/Author"):
        collective = el.findtext("CollectiveName")
        if collective:
            out.append(" ".join(collective.split()))
            continue
        last = (el.findtext("LastName") or "").strip()
        initials = (el.findtext("Initials") or "").strip()
        if last:
            out.append(f"{last} {initials}".strip())
    return out[:MAX_AUTHORS]


def parse_pubmed_xml(xml: bytes) -> list[dict]:
    root = ET.fromstring(xml)
    out = []
    for rec in root.findall("PubmedArticle"):
        citation = rec.find("MedlineCitation")
        pmid = (citation.findtext("PMID") or "").strip() if citation is not None else ""
        article = citation.find("Article") if citation is not None else None
        if not pmid.isdigit() or article is None:
            continue
        title = _text(article.find("ArticleTitle"))
        if not title:
            continue
        doi = next((" ".join((i.text or "").split()) for i in rec.findall("PubmedData/ArticleIdList/ArticleId")
                    if i.get("IdType") == "doi" and (i.text or "").strip()), None)
        journal = article.findtext("Journal/Title") or article.findtext("Journal/ISOAbbreviation")
        out.append({
            "pmid": pmid,
            "doi": doi[:255] if doi else None,
            "title": title,
            "authors": _authors(article),
            "journal": " ".join(journal.split()) if journal else None,
            "pubYear": _year(article),
            "abstract": _abstract(article),
            "publicationTypes": [t for t in (_text(p) for p in article.findall("PublicationTypeList/PublicationType"))
                                 if t],
        })
    return out


def fetch_details(pmids: list[str]) -> list[dict]:
    resp = _ncbi("efetch.fcgi", {"id": ",".join(pmids), "retmode": "xml"})
    try:
        return parse_pubmed_xml(resp.content)
    except ET.ParseError as e:
        raise LiteratureError(f"A PubMed válasza nem értelmezhető: {e}") from e


def citation_counts(pmids: list[str]) -> dict[str, int]:
    """PMID → hivatkozási szám az NIH iCite-ból (kulcs nélkül, PMID alapján). Hiba esetén üres (a szám
    "nem ismert", a keresés ettől még kész) — a hiányzó szám nem 0. A források eltérő számot adnak,
    ezért a felület a forrást is kiírja (CITATION_SOURCE)."""
    out: dict[str, int] = {}
    for i in range(0, len(pmids), ICITE_BATCH):
        batch = pmids[i:i + ICITE_BATCH]
        try:
            resp = requests.get(ICITE, params={"pmids": ",".join(batch), "fl": "pmid,citation_count"},
                                headers={"User-Agent": "mediai (literature research)"},
                                timeout=config.LITERATURE_TIMEOUT_SEC)
            resp.raise_for_status()
            results = resp.json().get("data") or []
        except (requests.RequestException, ValueError) as e:
            log.warning("Az iCite nem érhető el, a hivatkozási számok hiányoznak: %s", e)
            return out
        for pub in results:
            count = pub.get("citation_count")
            if isinstance(count, int) and count >= 0:
                out[str(pub.get("pmid"))] = count
    return out
