"""RAG-darabolás: átfedő ablakok a kinyert szövegen, szóhatáron vágva.

Csak (charStart, charEnd) megy a Springnek — a darab szövegét a Spring vágja ki
(Document.textSpan), így pozíció és szöveg nem mondhat ellent egymásnak.
"""
import config


def chunk_ranges(text: str, target: int = config.CHUNK_TARGET_CHARS,
                 overlap: int = config.CHUNK_OVERLAP_CHARS) -> list[tuple[int, int]]:
    n = len(text)
    ranges: list[tuple[int, int]] = []
    start = 0
    while start < n:
        # a darab ne kezdődjön/végződjön whitespace-szel
        while start < n and text[start].isspace():
            start += 1
        if start >= n:
            break
        end = min(start + target, n)
        if end < n:
            # visszalépés az utolsó bekezdés-, sor- vagy szóhatárig (de legalább a fél ablakig)
            floor = start + target // 2
            cut = max(text.rfind("\n\n", floor, end), text.rfind("\n", floor, end), text.rfind(" ", floor, end))
            if cut > start:
                end = cut
        trimmed_end = end
        while trimmed_end > start and text[trimmed_end - 1].isspace():
            trimmed_end -= 1
        if trimmed_end > start and (not ranges or ranges[-1] != (start, trimmed_end)):
            ranges.append((start, trimmed_end))
        if end >= n:
            break
        # a következő ablak az átfedéssel kezdődik, szóhatáron
        next_start = max(end - overlap, start + 1)
        space = text.find(" ", next_start, end)
        start = space + 1 if space >= 0 else next_start
    return ranges
