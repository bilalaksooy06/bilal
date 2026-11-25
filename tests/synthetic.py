"""Testler için rastgele Meowdoku tahtası ve ekran görüntüsü üretir."""

from __future__ import annotations

import random

from PIL import Image, ImageDraw

PASTELS = [
    (255, 179, 186), (255, 223, 186), (255, 255, 186), (186, 255, 201),
    (186, 225, 255), (210, 190, 255), (255, 190, 240), (190, 240, 240),
    (240, 200, 150), (200, 230, 150), (170, 200, 230), (230, 170, 170),
    (150, 220, 190), (250, 210, 230), (200, 200, 250), (235, 235, 160),
]


def random_cats(n: int, rng: random.Random):
    """Birbirine değmeyen, her satır/sütunda bir kedi olan rastgele yerleşim."""
    cols = list(range(n))

    def rec(r, prev, used, out):
        if r == n:
            return True
        order = cols[:]
        rng.shuffle(order)
        for c in order:
            if c in used or (prev is not None and abs(c - prev) <= 1):
                continue
            out.append(c)
            used.add(c)
            if rec(r + 1, c, used, out):
                return True
            out.pop()
            used.discard(c)
        return False

    out = []
    assert rec(0, None, set(), out)
    return [(r, c) for r, c in enumerate(out)]


def random_puzzle(n: int, seed: int = 0):
    """Çözümü garantili rastgele tahta: her kediden başlayıp bölgeleri büyütür."""
    rng = random.Random(seed)
    cats = random_cats(n, rng)
    regions = [[-1] * n for _ in range(n)]
    frontier = []
    for i, (r, c) in enumerate(cats):
        regions[r][c] = i
        frontier.append((r, c))
    while frontier:
        idx = rng.randrange(len(frontier))
        r, c = frontier[idx]
        nbrs = [(r + dr, c + dc) for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1))
                if 0 <= r + dr < n and 0 <= c + dc < n and regions[r + dr][c + dc] == -1]
        if not nbrs:
            frontier.pop(idx)
            continue
        rr, cc = rng.choice(nbrs)
        regions[rr][cc] = regions[r][c]
        frontier.append((rr, cc))
    return regions, cats


def render(regions, width=1080, height=2400, top=650, margin=60, line=3, border=8, bg=(250, 245, 235)):
    """Telefon ekranına benzeyen bir görsel çizer (üstte başlık, ortada tahta)."""
    n = len(regions)
    img = Image.new("RGB", (width, height), bg)
    d = ImageDraw.Draw(img)
    d.rectangle([100, 120, 980, 260], fill=(255, 140, 90))  # renkli başlık şeridi
    size = width - 2 * margin
    cell = size / n
    x0, y0 = margin, top
    for r in range(n):
        for c in range(n):
            d.rectangle([x0 + c * cell, y0 + r * cell, x0 + (c + 1) * cell, y0 + (r + 1) * cell],
                        fill=PASTELS[regions[r][c] % len(PASTELS)])
    for k in range(n + 1):
        d.line([x0 + k * cell, y0, x0 + k * cell, y0 + size], fill=(90, 80, 70), width=line)
        d.line([x0, y0 + k * cell, x0 + size, y0 + k * cell], fill=(90, 80, 70), width=line)
    # kalın bölge sınırları
    for r in range(n):
        for c in range(n):
            if c + 1 < n and regions[r][c] != regions[r][c + 1]:
                x = x0 + (c + 1) * cell
                d.line([x, y0 + r * cell, x, y0 + (r + 1) * cell], fill=(40, 30, 30), width=border)
            if r + 1 < n and regions[r][c] != regions[r + 1][c]:
                y = y0 + (r + 1) * cell
                d.line([x0 + c * cell, y, x0 + (c + 1) * cell, y], fill=(40, 30, 30), width=border)
    d.rectangle([x0, y0, x0 + size, y0 + size], outline=(40, 30, 30), width=border)
    return img, (x0, y0, x0 + size, y0 + size)
