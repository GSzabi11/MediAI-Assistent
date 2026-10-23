package bme.mediai.research.persistence;

// PUBMED: NCBI E-utilities; COCHRANE: ugyanaz a PubMed-keresés a Cochrane Database of Systematic
// Reviews-ra szűrve; EUROPE_PMC: szabad szavas keresés a Google Scholar helyett (csak PMID-es
// találatok) — docs/literature-research.md
public enum ResearchSource {
    PUBMED,
    COCHRANE,
    EUROPE_PMC
}
