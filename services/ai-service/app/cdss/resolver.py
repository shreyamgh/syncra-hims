"""Finds the generics in a prescription line ("Tab Dolo 650", "Combiflam", "Inj Ceftriaxone 1 g")."""

import re
from dataclasses import dataclass

from app.cdss.knowledge import KnowledgeBase

_TOKEN = re.compile(r"[a-z0-9]+(?:[-'][a-z0-9]+)*")


@dataclass(frozen=True)
class Ingredient:
    generic: str
    #: strength in mg per dose unit when known (from the text or the brand's composition)
    strength_mg: float | None


def _normalise(text: str) -> str:
    return " " + " ".join(_TOKEN.findall(text.lower())) + " "


class Resolver:
    """Whole-word matching of generic names, synonyms and brands; longest names first."""

    def __init__(self, kb: KnowledgeBase) -> None:
        self._kb = kb
        names: list[tuple[str, list[tuple[str, float | None]]]] = []
        for g in kb.generics:
            for name in [g.name, g.id.replace("_", " "), *g.synonyms]:
                names.append((_normalise(name).strip(), [(g.id, None)]))
        for brand, parts in kb.brands.items():
            names.append((_normalise(brand).strip(), list(parts.items())))
        self._names = sorted(names, key=lambda n: -len(n[0]))

    def generics_in(self, text: str) -> list[str]:
        return [i.generic for i in self.ingredients(text)]

    def ingredients(self, text: str) -> list[Ingredient]:
        normalised = _normalise(text)
        found: dict[str, float | None] = {}
        consumed = normalised
        for name, parts in self._names:
            needle = f" {name} "
            position = consumed.find(needle)
            if position < 0:
                continue
            after = consumed[position + len(needle):]
            consumed = consumed[:position] + " " * len(needle) + after
            single = len(parts) == 1
            for generic, fixed in parts:
                strength = fixed if fixed is not None else (_strength_after(after, explicit_only=not single)
                                                            if single else None)
                if generic not in found or found[generic] is None:
                    found[generic] = strength
        if len(found) == 1:
            only = next(iter(found))
            if found[only] is None:
                found[only] = _first_strength(normalised)
        return [Ingredient(g, s) for g, s in found.items()]


_STRENGTH = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*(mg|g|gm|mcg|ug)?\b")
_ANY_STRENGTH = re.compile(r"(\d+(?:\.\d+)?)\s*(mg|g|gm|mcg|ug)\b")


def _to_mg(value: float, unit: str | None) -> float:
    return {"g": value * 1000, "gm": value * 1000, "mcg": value / 1000, "ug": value / 1000}.get(unit or "mg", value)


def _strength_after(text: str, explicit_only: bool) -> float | None:
    """The strength written right after a name ("Dolo 650", "Paracetamol 500 mg")."""
    match = _STRENGTH.match(text)
    if not match or (explicit_only and match.group(2) is None):
        return None
    return _to_mg(float(match.group(1)), match.group(2))


def _first_strength(text: str) -> float | None:
    match = _ANY_STRENGTH.search(text)
    return _to_mg(float(match.group(1)), match.group(2)) if match else None
