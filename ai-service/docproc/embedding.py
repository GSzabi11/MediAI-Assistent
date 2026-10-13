"""Dense embedding a RAG-darabokhoz, a helyben futó Ollamán keresztül (EMBEDDING_MODEL, pl.
qwen3-embedding:4b). Hálózati modell-letöltés nincs: az Ollama /api/embed nem húz le modellt,
ha nincs letöltve, a hívás hibával áll le (előtte: ollama pull <modell>).

A pgvector oszlop vector(1024) (V12): a modell natív dimenziójából (qwen3-embedding:4b → 2560)
az Ollama `dimensions` paraméterével kérünk 1024-et (a Qwen3-Embedding MRL-lel tanult, a vektor
elejének levágása + normalizálás a támogatott út). Az eredmény L2-normált."""
import requests

import config

DIMENSION = 1024
BATCH_SIZE = 8

# A Qwen3-Embedding a keresőkérdés elé feladat-utasítást vár (a dokumentum-darabok elé nem);
# nélküle a visszakeresés a modell leírása szerint 1–5%-kal gyengébb
_QUERY_INSTRUCTION = ("Instruct: Adott egy orvosi kérdés egy beteg dokumentációjáról; keresd meg a"
                      " választ tartalmazó dokumentum-részleteket\nQuery: ")


def embed(texts: list[str]) -> list[list[float]]:
    """Dokumentum-darabok vektorai."""
    result: list[list[float]] = []
    for i in range(0, len(texts), BATCH_SIZE):
        result.extend(_embed_batch(texts[i:i + BATCH_SIZE]))
    return result


def embed_query(query: str) -> list[float]:
    """A keresőkérdés vektora (Qwen3-Embedding-nél utasítás-előtaggal)."""
    if config.EMBEDDING_MODEL.lower().startswith("qwen3-embedding"):
        query = _QUERY_INSTRUCTION + query
    return _embed_batch([query])[0]


def _embed_batch(texts: list[str]) -> list[list[float]]:
    resp = requests.post(
        f"{config.OLLAMA_URL}/api/embed",
        json={"model": config.EMBEDDING_MODEL, "input": texts, "dimensions": DIMENSION, "truncate": True},
        timeout=config.LLM_TIMEOUT_SEC,
    )
    if resp.status_code == 404:
        raise RuntimeError(f"Az embedding-modell ({config.EMBEDDING_MODEL}) nincs letöltve az Ollamában:"
                           f" ollama pull {config.EMBEDDING_MODEL}")
    resp.raise_for_status()
    vectors = resp.json().get("embeddings") or []
    if len(vectors) != len(texts):
        raise ValueError(f"Az Ollama {len(vectors)} vektort adott {len(texts)} szövegre")
    result = [[float(x) for x in v] for v in vectors]
    for v in result:
        if len(v) != DIMENSION:
            raise ValueError(f"Váratlan embedding-dimenzió: {len(v)} (elvárt: {DIMENSION})")
    return result
