"""A modell-kimenet determinisztikus utófeldolgozása: visszakeresés a szövegben, számértelmezés.

Elv: a modell csak javasol. Ami nem található meg szó szerint a dokumentumban, ami nem
szám, vagy ami a backend korlátaiba nem fér bele kerekítés nélkül, az KIMARAD — és a
kihagyás oka a figyelmeztetések közé kerül (a Spring a dokumentumon tárolja), sosem csendes.
"""
import datetime as dt
import re
from decimal import Decimal, InvalidOperation

from textutil import cells, contains_phrase, find_span, fold, join_wrapped

# ---------------------------------------------------------------- dátum

_HU_MONTHS = ["januar", "februar", "marcius", "aprilis", "majus", "junius", "julius",
              "augusztus", "szeptember", "oktober", "november", "december"]
_NUMERIC_DATE = re.compile(r"(?<!\d)(\d{4})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(\d{1,2})(?!\d)")
_TEXT_DATE = re.compile(r"(?<!\d)(\d{4})\.?\s*(" + "|".join(_HU_MONTHS) + r")\s*(\d{1,2})(?!\d)")
# A születési dátum nem klinikai dátum: ha előtte ennyi karakteren belül "szül" áll, nem jelölt
_BIRTH_WINDOW = 30


def dates_in_text(text: str) -> set[dt.date]:
    found: set[dt.date] = set()
    # Soronként: a "szül." csak a SAJÁT sorában, az előző dátum utáni részben jelöl születési
    # dátumot — különben a "Szül.: 1961.03.14.\nDátum: 2026.10.02." második dátuma is kiesne
    for line in text.splitlines():
        folded = fold(line)  # ékezet nélkül: a hónapnév-minta egyszerű marad
        matches = sorted(list(_NUMERIC_DATE.finditer(folded)) + list(_TEXT_DATE.finditer(folded)),
                         key=lambda m: m.start())
        prev_end = 0
        for m in matches:
            prefix = folded[max(prev_end, m.start() - _BIRTH_WINDOW):m.start()]
            prev_end = m.end()
            if "szul" in prefix:
                continue
            year, month, day = m.group(1), m.group(2), m.group(3)
            month_num = _HU_MONTHS.index(month) + 1 if month in _HU_MONTHS else int(month)
            try:
                found.add(dt.date(int(year), month_num, int(day)))
            except ValueError:
                pass
    return found


def resolve_clinical_date(model_date: str | None, text: str, warnings: list[str]) -> dt.date | None:
    """A modell dátuma, ha szerepel a szövegben (nem születési dátumként); különben, ha a
    szövegben egyetlen (nem születési) dátum van, az; különben None."""
    candidates = dates_in_text(text)
    if model_date:
        try:
            proposed = dt.date.fromisoformat(model_date.strip())
            if proposed in candidates:
                return proposed
            warnings.append(f"A modell által javasolt dátum ({model_date}) nem szerepel a dokumentumban")
        except ValueError:
            warnings.append(f"A modell által javasolt dátum nem értelmezhető: {model_date!r}")
    if len(candidates) == 1:
        only = next(iter(candidates))
        warnings.append(f"Klinikai dátum: a dokumentum egyetlen dátuma ({only.isoformat()})")
        return only
    return None


# ---------------------------------------------------------------- számok

_NUMBER = re.compile(r"^[+-]?\d+(?:[.,]\d+)?$")
_REF_NUM = r"([+-]?\d+(?:[.,]\d+)?)"
# A tartomány után a leletek többségén mértékegység áll ("4.1 - 5.9 mmol/L", "> 90 ml/perc/1.73 m2"):
# nem számjeggyel / tizedesjellel kezdődő farok, ami a határértékeket nem érinti
_UNIT_TAIL = r"(?:\s*[^\d\s.,<>=≤≥+\-\u2013\u2014\u2212].*)?"
_RANGE = re.compile(r"^\s*" + _REF_NUM + r"\s*[-\u2013\u2014\u2212\u00ad]\s*" + _REF_NUM + _UNIT_TAIL + r"\s*$")
_UPPER = re.compile(r"^\s*(?:<|<=|≤|max\.?)\s*" + _REF_NUM + _UNIT_TAIL + r"\s*$", re.IGNORECASE)
_LOWER = re.compile(r"^\s*(?:>|>=|≥|min\.?)\s*" + _REF_NUM + _UNIT_TAIL + r"\s*$", re.IGNORECASE)
# Cenzorált eredmény (">90", "< 0,5", "≥ 1000 mg/L"): összehasonlító jel + határérték
_CENSORED = re.compile(r"^\s*(<=|>=|≤|≥|<|>)\s*" + _REF_NUM + _UNIT_TAIL + r"\s*$")
_COMPARATORS = {"<": "<", "<=": "<=", "≤": "<=", ">": ">", ">=": ">=", "≥": ">="}
# Pontos érték, utána mértékegységgel ("0 /HPF", "1028 g/L")
_EXACT_WITH_UNIT = re.compile(r"^\s*" + _REF_NUM + _UNIT_TAIL + r"\s*$")
# LOINC-szerű kód (pl. "22664-7")
_CODE = re.compile(r"^\d{1,8}-\d$")
_WORD = re.compile(r"\w+")


def parse_decimal(raw: str, integer_digits: int, fraction_digits: int) -> Decimal | None:
    """Magyar (vesszős) vagy pontos tizedes; None, ha nem tiszta szám vagy nem fér bele
    a backend NUMERIC korlátjába (kerekíteni nem szabad)."""
    s = raw.strip().replace(" ", "")
    if not _NUMBER.match(s):
        return None
    try:
        value = Decimal(s.replace(",", "."))
    except InvalidOperation:
        return None
    sign, digits, exponent = value.as_tuple()
    fraction = max(0, -exponent)
    integer = max(1, len(digits) - fraction)
    if integer > integer_digits or fraction > fraction_digits:
        return None
    return value


def _number_in_text(raw: str, text: str) -> bool:
    """A szám ÖNÁLLÓ számként szerepel (pont vagy vessző tizedesjellel): a "0" ne egyezzen a
    "10.0" vagy a "2026" belsejével."""
    s = raw.strip()
    for variant in {s, s.replace(".", ","), s.replace(",", ".")}:
        if re.search(r"(?<![\d.,])" + re.escape(variant) + r"(?![\d]|[.,]\d)", text):
            return True
    return False


def _num(raw: str) -> Decimal | None:
    return parse_decimal(raw, 8, 4)


def parse_reference(raw: str) -> tuple[Decimal | None, Decimal | None] | None:
    """(ref_low, ref_high); None, ha nem értelmezhető. Egyoldalú tartomány megengedett,
    utána mértékegység állhat."""
    if m := _RANGE.match(raw):
        return _num(m.group(1)), _num(m.group(2))
    if m := _UPPER.match(raw):
        return None, _num(m.group(1))
    if m := _LOWER.match(raw):
        return _num(m.group(1)), None
    return None


def _reference_numbers(raw: str) -> list[str]:
    m = _RANGE.match(raw) or _UPPER.match(raw) or _LOWER.match(raw)
    return [g for g in m.groups() if g] if m else []


def _name_head(name: str) -> str:
    """A név zárójel előtti része ("MPV (Átlagos thrombocyta térfogat)" → "MPV")."""
    head = name.split("(")[0].strip()
    return head if len(head) >= 2 else name


def _head_pos(folded_line: str, head: str, allow_paren: bool = False) -> int:
    """A név elejének helye a (fold-olt) sorban, vagy -1. Csak szóhatáron ("Fehérvérsejt" ≠
    "Fehérvérsejtszám"). Alapból nem egy zárójeles részben — a "Vizelet" ne egyezzen a
    "pH (vizelet)" sorral; `allow_paren`-nel igen (a "C reaktív protein (CRP)" sorban a "CRP")."""
    pos = folded_line.find(head)
    while pos >= 0:
        end = pos + len(head)
        word_start = pos == 0 or not folded_line[pos - 1].isalnum()
        word_end = end == len(folded_line) or not folded_line[end].isalnum()
        if word_start and word_end and (allow_paren or not folded_line[:pos].rstrip().endswith("(")):
            return pos
        pos = folded_line.find(head, pos + 1)
    return -1


def _text_in_line(value: str, line: str) -> bool:
    """Szöveges érték ("Negatív", "kevés") szóhatáron, kis/nagybetű- és ékezetfüggetlenül."""
    return _head_pos(fold(line), fold(value), allow_paren=True) >= 0


# A lelet "*" oszlopának jelzései és a státusz-oszlop — nem referencia
_FLAG_CELLS = {"magas", "alacsony", "koros", "kritikus", "*"}
_CELL_SPLIT = re.compile(r"\s{2,}")


def _text_ref_from_line(line: str, value_text: str) -> str | None:
    """Szöveges referencia a táblázatsor oszlopaiból: a szöveges érték UTÁNI cella, a "magas"/
    "alacsony" jelzést átugorva, a státusznál ("VALIDÁLT") megállva. Determinisztikus pótlás:
    a modell a szöveges referenciát ("Negatív") gyakran nem adta vissza."""
    cells = [c.strip() for c in _CELL_SPLIT.split(line.strip()) if c.strip()]
    target = fold(value_text)
    try:
        start = next(i for i, c in enumerate(cells) if fold(c) == target)
    except StopIteration:
        return None
    for cell in cells[start + 1:]:
        folded = fold(cell)
        if folded in _FLAG_CELLS or set(cell) <= set("+-"):  # "magas", "+", "++", "-": minősítés
            continue
        if folded.startswith("validalt") or any(ch.isdigit() for ch in cell) or not any(ch.isalpha() for ch in cell):
            return None
        return cell if len(cell) <= 128 else None
    return None


def _matching_lines(lines: list[str], name: str, raw_value: str, comparator: str | None,
                    allow_paren: bool = False, text_value: bool = False) -> list[int]:
    """Azok a sorok, amelyekben a vizsgálat neve áll, és az érték (cenzorált értéknél a jel is)
    a név UTÁNI cellában, vagyis az Eredmény oszlopban van — nem a referencia oszlopában:
    a "Vizelet mikroalbumin 24h ürítés  Nem számolható  < 30.0" sorban a "< 30.0" nem eredmény.
    Ha a sorban nincs oszlophatár (egy szóközös PDF), a név utáni szövegrészben keres."""
    head = fold(_name_head(name))
    signs = {"<": "<", "<=": "<≤", ">": ">", ">=": ">≥"}.get(comparator or "", "")
    # Zárójeles egyezés csak NAGYBETŰS rövidítésre ("C reaktív protein (CRP)" → "CRP"); a
    # "Vizelet" ne kapja meg a "pH (vizelet)" sor értékét, ha a modell a pH-t kihagyta
    letters = [c for c in _name_head(name) if c.isalpha()]
    if allow_paren and not (letters and all(c.isupper() for c in letters)):
        return []
    out = []
    for i, line in enumerate(lines):
        row_cells = cells(line)
        target = None
        for k, (_, _, cell) in enumerate(row_cells):
            folded_cell = fold(cell)
            pos = _head_pos(folded_cell, head, allow_paren)
            if pos < 0:
                continue
            if k + 1 < len(row_cells):
                target = row_cells[k + 1][2]
            else:
                # egy cellás sor: csak ha az érték KÖZVETLENÜL a név után áll — a "Látótér ->
                # mikroliter átváltás: 1HPF => 0,18ul" megjegyzés nem eredmény
                rest = folded_cell[pos + len(head):].lstrip(" ,:")
                first = rest.split(" ")[0] if rest else ""
                target = rest if first and (first[0].isdigit() or first[0] in "<>≤≥"
                                            or (text_value and first[0].isalpha())) else ""
            break
        if target is None:
            continue
        if text_value:
            ok = _text_in_line(raw_value, target)
        else:
            ok = _number_in_text(raw_value, target) and (not signs or any(c in target for c in signs))
        if ok:
            out.append(i)
    return out


def _cells_after_value(line: str, name: str) -> list[str]:
    """A sor cellái az Eredmény oszlop UTÁN (minősítés, referencia, mértékegység, státusz)."""
    head = fold(_name_head(name))
    row_cells = [c[2] for c in cells(line)]
    for allow_paren in (False, True):
        for k, cell in enumerate(row_cells):
            if _head_pos(fold(cell), head, allow_paren) >= 0:
                return row_cells[k + 2:]
    return []


def _value_cell(line: str, name: str) -> str | None:
    head = fold(_name_head(name))
    row_cells = [c[2] for c in cells(line)]
    for allow_paren in (False, True):
        for k, cell in enumerate(row_cells):
            if _head_pos(fold(cell), head, allow_paren) >= 0:
                return row_cells[k + 1] if k + 1 < len(row_cells) else None
    return None


def _unit_from_row(line: str, name: str, number_raw: str | None, exclude: tuple[str, ...] = ()) -> str | None:
    """Mértékegység a sorból, ha a modell nem adta: az eredmény-cella szám utáni része
    ("8.8 fL" → "fL"), vagy a sor utolsó cellája (Synlab: külön Mértékegység oszlop)."""
    value_cell = _value_cell(line, name)
    if value_cell and number_raw:
        m = re.match(r"^\s*(?:<=|>=|<|>|≤|≥)?\s*" + re.escape(number_raw.strip()) + r"\s+(\S.*)$", value_cell)
        if m and len(m.group(1)) <= 32:
            return m.group(1).strip()
    rest = _cells_after_value(line, name)
    if rest:
        last = rest[-1]
        # Mértékegység-alak: "/" vagy "%" van benne, vagy rövid ("fL", "pg", "óra") — a szöveges
        # referencia ("Negatív") és a státusz ("VALIDÁLT") nem az
        unit_like = "/" in last or "%" in last or len(last) <= 5
        if (unit_like and len(last) <= 32 and not fold(last).startswith("validalt")
                and parse_reference(last) is None and fold(last) not in _FLAG_CELLS
                and fold(last) not in {fold(c) for c in exclude}):
            return last
    return None


_LEADING_FLAGS = re.compile(r"^\s*(?:(?:[+-]+|magas|alacsony|kóros)\s+)+", re.IGNORECASE)


def _strip_flags(raw_ref: str) -> str:
    """A minősítés ("-"/"+" jel, "alacsony"/"magas") a referencia elé csúszhat a modell
    kimenetében ("- 0.05 - 0.45", "alacsony 0.05 - 0.45") — levágjuk. Negatív szám ("-5 - 5") nem érintett (nincs utána szóköz)."""
    return _LEADING_FLAGS.sub("", raw_ref)


def _reanchor(raw_value: str, lines: list[str], name: str | None = None) -> tuple[str, str, str | None] | None:
    """A modell egy alcímet ("Automata mikroszkópos értékelés", "KLINIKAI KÉMIA") adott névnek,
    és a valódi sort értéknek ("Fehérvérsejt 0 < 4 /HPF", "Glükóz - szérum (A)"). Ha az "érték"
    egy táblázatsor névcellájával kezdődik, abból a sorból olvasunk: (név, eredmény, referencia).
    Ha a modell értéke használhatatlan ("<", "----"), de a NEVE egy sor névcellája, az Eredmény
    oszlopot olvassuk ki abból a sorból (`name`)."""
    folded_value = fold(raw_value)
    folded_name = fold(name) if name else None
    for line in lines:
        row_cells = [c[2] for c in cells(line)]
        k = next((i for i, c in enumerate(row_cells)
                  if any(ch.isalpha() for ch in c) and not _CODE.match(c)), None)
        if k is None or k + 1 >= len(row_cells):
            continue
        name_cell = fold(row_cells[k])
        by_value = folded_value == name_cell or folded_value.startswith(name_cell + " ")
        by_name = folded_name is not None and folded_name in (name_cell, fold(_ACCREDITED.sub("", row_cells[k])))
        if len(name_cell) < 3 or not (by_value or by_name):
            continue
        if by_name and not by_value and not any(ch.isdigit() or ch.isalpha() for ch in row_cells[k + 1]):
            continue  # az Eredmény oszlop sem értelmezhető (pl. "----"): nincs mit kiolvasni
        ref = next((c for c in row_cells[k + 2:] if parse_reference(_strip_flags(c)) not in (None, (None, None))), None)
        return row_cells[k], row_cells[k + 1], ref
    return None


def _row_code(line: str, name: str) -> str | None:
    """A vizsgálat kódja (pl. LOINC "22664-7") a saját sorából, a név ELŐTTI kód-alakú szó —
    determinisztikusan, nem a modellből: a modell a kódoszlopot egy sorral elcsúsztatta
    (mérve: a Karbamid a Kreatinin kódját kapta). Ha nincs pontosan egy jelölt, None."""
    folded = fold(line)
    head = fold(_name_head(name))
    pos = _head_pos(folded, head)
    if pos < 0:
        pos = _head_pos(folded, head, allow_paren=True)
    codes = {tok for tok in folded[:max(pos, 0)].split() if _CODE.match(tok)}
    return codes.pop() if len(codes) == 1 else None


def _name_in_text(text: str, name: str) -> bool:
    """A név szó szerint a leletben, vagy — ha a táblázat a nevet több sorba tördelte
    ("MPV (Átlagos thrombocyta" … "térfoga") — minden szava megvan benne."""
    if contains_phrase(text, name):
        return True
    words = _WORD.findall(name)
    return bool(words) and all(contains_phrase(text, w) for w in words)


def _clean(s, max_len: int | None = None) -> str | None:
    if not isinstance(s, str) or not s.strip() or s.strip().lower() in ("null", "none"):
        return None  # a modell néha szövegként adja a JSON null-t
    s = s.strip()
    return None if max_len and len(s) > max_len else s


# ---------------------------------------------------------------- labor

# A Synlab a név után "(A)"-val jelöli az akkreditált vizsgálatot (a lelet lábjegyzete szerint) —
# ez nem a vizsgálat neve: levágva egyezik a más leleteken szereplő névvel (egy trend legyen)
_ACCREDITED = re.compile(r"\s*\(A\)\s*$")


def _section_headers(lines: list[str]) -> list[str | None]:
    """Soronként az érvényes főfejléc (NAGYBETŰS sor, pl. "KLINIKAI KÉMIA", "VIZELET VIZSGÁLATOK"),
    vagy None. A "VALIDÁLT" státusz és az "(A)" jelölés nem fejléc."""
    current: str | None = None
    out: list[str | None] = []
    for line in lines:
        stripped = line.strip()
        letters = [c for c in stripped if c.isalpha()]
        if (len(letters) >= 4 and not any(c.isdigit() for c in stripped)
                and all(c.isupper() for c in letters) and len(stripped) <= 60
                and not fold(stripped).startswith("validalt") and not stripped.endswith(":")):
            current = stripped
        out.append(current)
    return out


def canonical_lab_name(name: str, section: str | None) -> str:
    """Egységes vizsgálatnév a trendhez: az akkreditációs "(A)" jelölés nélkül, és ha a sor
    a VIZELET fejléc alatt áll, "(vizelet)" végződéssel — a Synlab egyik elrendezése "Cukor"-t
    ír a "VIZELET VIZSGÁLATOK" fejléc alatt, a másik "Cukor (vizelet)"-et ugyanarra."""
    canonical = name
    while _ACCREDITED.search(canonical):
        canonical = _ACCREDITED.sub("", canonical)
    canonical = re.sub(r"\s+", " ", canonical).strip()
    if section and "vizelet" in fold(section) and "vizelet" not in fold(canonical):
        canonical += " (vizelet)"
    return canonical


def _section_title(section: str) -> str:
    """"KLINIKAI KÉMIA" → "Klinikai kémia" (csoportnévnek)."""
    lower = section.lower()
    return lower[:1].upper() + lower[1:]


def lab_results(data: dict, text: str, warnings: list[str]) -> list[dict]:
    rows = data.get("eredmenyek") or []
    # A tördelt nevek folytatása visszafűzve ("Tireoidea stimuláló hormon … (TSH)" egy sor)
    lines = join_wrapped(text).splitlines()
    sections = _section_headers(lines)

    # 1. Soronkénti ellenőrzés: név a leletben, érték szám (pontos vagy cenzorált)
    items: list[dict] = []
    seen_items: set[tuple] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = _clean(row.get("nev"), 255)
        raw_value = _clean(row.get("ertek"))
        if not name or not raw_value:
            continue
        if sum(c.isalpha() for c in name) < 2:
            # A modell a sor egy cellájából ("+", ">1030") csinált nevet, a valódi név a csoportban
            # maradt ("Fajsúly") — ha az egy táblázatsor neve, abból a sorból olvasunk
            group_name = _clean(row.get("csoport"), 255)
            anchored = _reanchor("", lines, group_name) if group_name else None
            if anchored is None:
                warnings.append(f"Laborsor kihagyva — a vizsgálat neve nem értelmezhető: {name!r}")
                continue
            name, raw_value, anchored_ref = anchored
            row = {**row, "nev": name, "ertek": raw_value, "referencia": anchored_ref, "csoport": None,
                   "mertekegyseg": None}
        if (_num(raw_value) is None and not _EXACT_WITH_UNIT.match(raw_value) and not _CENSORED.match(raw_value)
                and ((anchored := _reanchor(raw_value, lines)) is not None
                     or (not any(c.isalpha() for c in raw_value)
                         and (anchored := _reanchor(raw_value, lines, name)) is not None))):
            name, raw_value, anchored_ref = anchored
            row = {**row, "nev": name, "ertek": raw_value, "referencia": anchored_ref, "csoport": None,
                   "mertekegyseg": None}
        if not _name_in_text(text, name):
            warnings.append(f"Laborsor kihagyva — a vizsgálat neve nem található a leletben: {name!r}")
            continue
        # Pontos szám (mértékegységgel is: "0 /HPF"), vagy cenzorált érték (">90" → 90 +
        # comparator ">", FHIR Quantity mintára). Kvalitatív ("negatív") nem kerülhet be (CLAUDE.md).
        comparator = None
        number_raw = raw_value
        value = _num(raw_value)
        if value is None and (m := _EXACT_WITH_UNIT.match(raw_value)):
            number_raw = m.group(1)
            value = _num(number_raw)
        if value is None and (m := _CENSORED.match(raw_value)):
            comparator, number_raw = _COMPARATORS[m.group(1)], m.group(2)
            value = _num(number_raw)
        value_text = None
        if value is None:
            # Kvalitatív eredmény ("Negatív", "kevés") szó szerint, value_text-ként (V19). Csak
            # betűs, szám nélküli szöveg — a félig értelmezett "2-3 /LPF" nem lesz szöveges érték
            if (any(c.isalpha() for c in raw_value) and not any(c.isdigit() for c in raw_value)
                    and len(raw_value) <= 64):
                value_text = raw_value
            else:
                warnings.append(f"Laborsor kihagyva — nem értelmezhető érték: {name} = {raw_value}")
                continue
        item_key = (fold(canonical_lab_name(name, None)), fold(raw_value))
        if item_key in seen_items:
            continue
        seen_items.add(item_key)
        items.append({"row": row, "name": name, "raw_value": raw_value, "number_raw": number_raw,
                      "value": value, "value_text": value_text, "comparator": comparator, "line": None})

    # 2. Sor-párosítás két körben; egy táblázatsorból legfeljebb egy eredmény. Előbb a
    #    zárójelen kívüli névegyezések foglalnak, utána a zárójeles rövidítések ("(CRP)") a még
    #    szabad sorokon — így egy kitalált "Vizelet" sor nem kapja meg a "pH (vizelet)" értékét.
    used_lines: set[int] = set()
    for allow_paren in (False, True):
        for item in items:
            if item["line"] is not None:
                continue
            free = [i for i in _matching_lines(lines, item["name"], item["number_raw"], item["comparator"],
                                                allow_paren, item["value_text"] is not None)
                    if i not in used_lines]
            if free:
                item["line"] = free[0]
                used_lines.add(free[0])

    results: list[dict] = []
    seen: set[tuple] = set()
    for item in items:
        name, raw_value, number_raw = item["name"], item["raw_value"], item["number_raw"]
        value, comparator, row_line, row = item["value"], item["comparator"], item["line"], item["row"]
        value_text = item["value_text"]
        if row_line is None:
            if _matching_lines(lines, name, number_raw, comparator, True, value_text is not None):
                warnings.append(f"Laborsor kihagyva — a sora már egy másik vizsgálathoz tartozik: {name} = {raw_value}")
                continue
            warnings.append(f"Laborsor kihagyva — az érték nem található a vizsgálat sorában: {name} = {raw_value}")
            continue

        unit = row.get("mertekegyseg")
        unit_clean = _clean(unit, 32)
        if unit_clean is None and not (isinstance(unit, str) and unit.strip()) and row_line is not None:
            unit_clean = _unit_from_row(lines[row_line], name, None if value_text is not None else number_raw,
                                        tuple(x for x in (value_text, row.get("referencia"),
                                                          _text_ref_from_line(lines[row_line], value_text)
                                                          if value_text else None) if isinstance(x, str)))
        if unit and unit_clean is None and isinstance(unit, str) and unit.strip():
            warnings.append(f"{name}: a mértékegység túl hosszú, kimaradt: {unit!r}")

        ref_low = ref_high = None
        ref_text = None
        raw_ref = _clean(row.get("referencia"))
        if raw_ref:
            raw_ref = _clean(_strip_flags(raw_ref))
        ref_line = lines[row_line] if row_line is not None else text
        # A számos referencia csak a vizsgálat saját sorában, az eredmény UTÁNI oszlopokban lehet
        # (a modell a szomszéd sor referenciáját is odaírta egy üres referenciájú sorhoz)
        ref_scope = "  ".join(_cells_after_value(ref_line, name)) if row_line is not None else text
        if raw_ref is None and value_text is not None and row_line is not None:
            raw_ref = _text_ref_from_line(lines[row_line], value_text)
        # Szöveges referencia, ha nem számos tartomány; szöveges eredményhez (pl. "Nem számolható")
        # is tartozhat számos tartomány ("< 30.0 mg/nap"), az számként marad
        if raw_ref and parse_reference(raw_ref) is None and not any(c.isdigit() for c in raw_ref):
            # Szöveges referencia ("Negatív", "normális"): szó szerint a vizsgálat sorából
            if not any(c.isalpha() for c in raw_ref):
                pass  # "----": üres referencia-mező jelölése, nem referencia
            elif len(raw_ref) <= 128 and _text_in_line(raw_ref, ref_line):
                ref_text = raw_ref
            else:
                warnings.append(f"{name}: a szöveges referencia nem található a leletben, kimaradt: {raw_ref!r}")
        elif raw_ref:
            parsed = parse_reference(raw_ref)
            if parsed is None or parsed == (None, None):
                warnings.append(f"{name}: a referenciatartomány nem értelmezhető, kimaradt: {raw_ref!r}")
            elif parsed[0] is not None and parsed[1] is not None and parsed[0] > parsed[1]:
                warnings.append(f"{name}: fordított referenciatartomány, kimaradt: {raw_ref!r}")
            elif not all(_number_in_text(n, ref_scope) for n in _reference_numbers(raw_ref)):
                warnings.append(f"{name}: a referenciatartomány nem található a vizsgálat sorában, kimaradt: {raw_ref!r}")
            else:
                ref_low, ref_high = parsed

        section = sections[row_line] if row_line is not None else None
        stored_name = canonical_lab_name(name, section)
        # Csoport: a modellé, ha a leletben létező fejléc (a modell gyakran a vizsgálat nevét írta
        # csoportnak); különben a lelet nagybetűs főfejléce
        group = _clean(row.get("csoport"), 128)
        if group and (fold(group) in fold(name) or fold(group) in fold(stored_name)
                      or not contains_phrase(text, group)):
            group = None
        if group is None and section:
            group = _section_title(section)[:128]
        elif group and group.isupper():
            group = _section_title(group)

        key = (stored_name.casefold(), comparator, str(value), (value_text or "").casefold(), unit_clean)
        if key in seen:
            continue
        seen.add(key)
        results.append({
            "groupName": group,
            "name": stored_name,
            "value": None if value is None else str(value),
            "comparator": comparator,
            "valueText": value_text,
            "unit": unit_clean,
            "refLow": None if ref_low is None else str(ref_low),
            "refHigh": None if ref_high is None else str(ref_high),
            "refText": ref_text,
            "measuredAt": None,  # a Spring a dokumentum klinikai dátumát veszi (budapesti éjfél)
            "code": None if row_line is None else _row_code(lines[row_line], name),
            # belső mezők a PDF-beli sor megkereséséhez (locate.add_source_boxes veszi ki őket)
            "_head": _name_head(name),
            "_value": value_text if value_text is not None else number_raw,
            "_comparator": comparator,
        })
    return results


# ---------------------------------------------------------------- ambuláns lap

_LABELS = {
    "DIAGNOZIS": "Diagnózis", "PANASZ": "Panasz", "ANAMNEZIS": "Anamnézis", "STATUSZ": "Státusz",
    "VIZSGALAT": "Vizsgálat", "TERAPIA": "Terápia", "GYOGYSZER": "Gyógyszer", "ALLERGIA": "Allergia",
    "JAVASLAT": "Javaslat",
}
_UNCERTAINTY = {"LOW", "MEDIUM", "HIGH"}


def _medication(item: dict, quote: str, text: str, warnings: list[str]) -> dict | None:
    name = _clean(item.get("gyogyszer_nev"), 255)
    if not name:
        return None  # pl. gyógyszercsoport ("vérnyomáscsökkentő") név nélkül: csak állítás lesz
    if not contains_phrase(quote, name):
        warnings.append(f"Gyógyszer nem került a listára — a neve nem szerepel az idézetben: {name!r}")
        return None

    dose = unit = None
    raw_dose = _clean(item.get("dozis"))
    if raw_dose:
        dose = parse_decimal(raw_dose, 7, 3)  # medication.dose NUMERIC(10,3)
        unit = _clean(item.get("dozis_egyseg"), 255)
        if dose is None or dose <= 0 or not _number_in_text(raw_dose, quote) or unit is None:
            warnings.append(f"{name}: a dózis nem értelmezhető vagy nem szerepel az idézetben, kimaradt: "
                            f"{raw_dose!r} {item.get('dozis_egyseg')!r}")
            dose = unit = None

    # Hatóanyagot a modell ne "tudjon" a márkanévből: csak ha a lelet maga írja
    # (a márkanév → hatóanyag leképezés majd determinisztikus, RxNorm/DDInter alapú lesz)
    ingredient = _clean(item.get("hatoanyag"), 255)
    if ingredient and not contains_phrase(text, ingredient):
        ingredient = None
    route = _clean(item.get("alkalmazas"), 255)
    if route and not contains_phrase(text, route):
        route = None

    return {"name": name, "activeIngredient": ingredient,
            "dose": None if dose is None else str(dose), "doseUnit": unit, "route": route}


def ambulance_statements(data: dict, text: str, warnings: list[str]) -> list[dict]:
    items = data.get("adatok") or []
    statements: list[dict] = []
    seen: set[tuple] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        category = item.get("kategoria")
        claim = _clean(item.get("allitas"))
        quote = _clean(item.get("idezet"))
        uncertainty = item.get("bizonytalansag")
        if category not in _LABELS or not claim or not quote:
            continue
        if uncertainty not in _UNCERTAINTY:
            uncertainty = "HIGH"  # MODEL_INFERENCE-nél kötelező; ismeretlen = legóvatosabb

        span = find_span(text, quote)
        if span is None:
            warnings.append(f"Állítás kihagyva — az idézet nem található szó szerint a dokumentumban: {quote[:120]!r}")
            continue
        start, end = span
        quoted = text[start:end]

        # Gyógyszer/allergia csak a megfelelő kategóriánál: más kategóriájú elemhez csatolt
        # gyógyszer- vagy allergia-mezőt figyelmen kívül hagyunk
        medication = _medication(item, quoted, text, warnings) if category == "GYOGYSZER" else None
        allergy = None
        if category == "ALLERGIA":
            substance = _clean(item.get("allergen"), 255)
            if substance and contains_phrase(quoted, substance):
                allergy = {"substance": substance}
            else:
                warnings.append(f"Allergia nem került a listára — az anyag nem szerepel az idézetben: {substance!r}")

        key = (start, end, fold(claim))
        if key in seen:
            continue
        seen.add(key)
        statements.append({
            "text": claim,
            "label": _LABELS[category],
            # Súlyosság determinisztikusan a kategóriából — a modell nem minősít kritikusnak semmit
            "severity": "WARNING" if category == "ALLERGIA" else "INFO",
            "uncertainty": uncertainty,
            "charStart": start,
            "charEnd": end,
            "medication": medication,
            "allergy": allergy,
        })
    return statements
