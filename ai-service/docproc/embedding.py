"""BGE-M3 dense embedding (1024 dim) a RAG-darabokhoz. Egyszer töltődik be, a sorral
szekvenciálisan használjuk (a GPU-t a worker szál egyedül birtokolja)."""
import config

DIMENSION = 1024
_model = None


def _load():
    global _model
    if _model is None:
        import torch
        from sentence_transformers import SentenceTransformer

        device = config.EMBEDDING_DEVICE or ("cuda" if torch.cuda.is_available() else "cpu")
        _model = SentenceTransformer(config.EMBEDDING_MODEL, device=device)
        if device == "cuda":
            _model.half()  # ~1,1 GB VRAM fp16-ban — a MedGemma 4B mellett elfér 8 GB-on
    return _model


def embed(texts: list[str]) -> list[list[float]]:
    vectors = _load().encode(texts, batch_size=8, normalize_embeddings=True, convert_to_numpy=True)
    result = [[float(x) for x in v] for v in vectors]
    for v in result:
        if len(v) != DIMENSION:
            raise ValueError(f"Váratlan embedding-dimenzió: {len(v)} (elvárt: {DIMENSION})")
    return result
