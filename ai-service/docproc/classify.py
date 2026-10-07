"""Dokumentumtípus-besorolás — determinisztikus kulcsszó-keresés, NEM modell.

CLAUDE.md: determinisztikus szabályt sosem váltunk ki LLM-mel. Kis/nagybetű-, ékezet-,
whitespace- és sorvégi elválasztás-független ("LABORATÓRIUMI  LELET", "laboratoriumi\\nlelet",
"labora-\\ntóriumi lelet" mind egyezik).
"""
from textutil import contains_phrase

LAB = "LAB"
AMBULANCE = "AMBULANCE"

LAB_PHRASES = ("laboratóriumi lelet", "mikrobiológiai lelet")


def classify(text: str) -> str:
    return LAB if any(contains_phrase(text, p) for p in LAB_PHRASES) else AMBULANCE
