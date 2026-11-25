"""Meowdoku çözücü.

Kurallar:
  * Her satırda tam 1 kedi
  * Her sütunda tam 1 kedi
  * Her renkli bölgede tam 1 kedi
  * Kediler birbirine değemez (çapraz dahil 8 komşu)

Tahta, `regions[r][c] = bölge numarası` şeklinde NxN bir listedir.
Çözüm, `[(satır, sütun), ...]` şeklinde N kedinin konumudur.
"""

from __future__ import annotations

from typing import List, Optional, Sequence, Tuple

Cell = Tuple[int, int]


def _neighbours(r: int, c: int, n: int):
    for dr in (-1, 0, 1):
        for dc in (-1, 0, 1):
            if dr == 0 and dc == 0:
                continue
            rr, cc = r + dr, c + dc
            if 0 <= rr < n and 0 <= cc < n:
                yield rr, cc


def solve(regions: Sequence[Sequence[int]]) -> Optional[List[Cell]]:
    """Bulmacayı çözer; çözüm yoksa None döner.

    En az seçeneği kalan bölgeyi önce dolduran (MRV) geri izlemeli arama.
    16x16'ya kadar tahtalar milisaniyeler içinde çözülür.
    """
    n = len(regions)
    if n == 0 or any(len(row) != n for row in regions):
        raise ValueError("Tahta NxN olmalı")

    region_ids = sorted({v for row in regions for v in row})
    if len(region_ids) != n:
        raise ValueError(f"{n}x{n} tahtada {n} bölge olmalı, {len(region_ids)} bulundu")

    cells_of = {rid: [] for rid in region_ids}
    for r in range(n):
        for c in range(n):
            cells_of[regions[r][c]].append((r, c))

    # blocked[r][c] > 0 ise o hücreye kedi konamaz (sayaç; geri almak kolay olsun diye)
    blocked = [[0] * n for _ in range(n)]
    placed: List[Cell] = []
    remaining = set(region_ids)

    def block_for(r: int, c: int) -> List[Cell]:
        cells = [(r, cc) for cc in range(n)] + [(rr, c) for rr in range(n)]
        cells += list(_neighbours(r, c, n))
        cells += cells_of[regions[r][c]]
        return cells

    def search() -> bool:
        if not remaining:
            return True
        # En az serbest hücresi olan bölgeyi seç
        best_rid, best_opts = None, None
        for rid in remaining:
            opts = [(r, c) for (r, c) in cells_of[rid] if blocked[r][c] == 0]
            if not opts:
                return False
            if best_opts is None or len(opts) < len(best_opts):
                best_rid, best_opts = rid, opts
                if len(opts) == 1:
                    break
        # Satır/sütun kontrolü: kalan her satırda hâlâ serbest bir hücre olmalı
        used_rows = {r for r, _ in placed}
        used_cols = {c for _, c in placed}
        for r in range(n):
            if r not in used_rows and all(blocked[r][c] for c in range(n)):
                return False
        for c in range(n):
            if c not in used_cols and all(blocked[r][c] for r in range(n)):
                return False

        remaining.discard(best_rid)
        for r, c in best_opts:
            cells = block_for(r, c)
            for rr, cc in cells:
                blocked[rr][cc] += 1
            placed.append((r, c))
            if search():
                return True
            placed.pop()
            for rr, cc in cells:
                blocked[rr][cc] -= 1
        remaining.add(best_rid)
        return False

    return sorted(placed) if search() else None


def is_valid_solution(regions: Sequence[Sequence[int]], cats: Sequence[Cell]) -> bool:
    n = len(regions)
    if len(cats) != n:
        return False
    rows = {r for r, _ in cats}
    cols = {c for _, c in cats}
    regs = {regions[r][c] for r, c in cats}
    if len(rows) != n or len(cols) != n or len(regs) != n:
        return False
    for i, (r1, c1) in enumerate(cats):
        for r2, c2 in cats[i + 1:]:
            if abs(r1 - r2) <= 1 and abs(c1 - c2) <= 1:
                return False
    return True


def format_board(regions: Sequence[Sequence[int]], cats: Sequence[Cell] = ()) -> str:
    """Terminalde göstermek için tahtayı metne çevirir (kedi = '🐱' yerine '#')."""
    cat_set = set(cats)
    symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    lines = []
    for r, row in enumerate(regions):
        parts = []
        for c, rid in enumerate(row):
            ch = symbols[rid % len(symbols)]
            parts.append(f"[{ch}]" if (r, c) in cat_set else f" {ch.lower()} ")
        lines.append("".join(parts))
    return "\n".join(lines)
