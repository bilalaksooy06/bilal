"""Ekran görüntüsünden Meowdoku tahtasını okur.

Adımlar:
  1. Renkli (doygun) piksellerin yoğun olduğu kare alanı bul -> tahta sınırları
  2. Tahtadaki çizgilerin aralığından N'yi (satır/sütun sayısı) bul
  3. Her hücrenin ortasındaki rengi al, renkleri tam N gruba ayır -> bölgeler

Otomatik tespit tutmazsa `grid` (x1, y1, x2, y2) ve `size` (N) elle verilebilir.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np
from PIL import Image, ImageDraw

Box = Tuple[int, int, int, int]  # x1, y1, x2, y2 (x2/y2 hariç)


@dataclass
class Board:
    box: Box
    size: int
    regions: List[List[int]]
    colors: np.ndarray  # (N, N, 3) hücre renkleri

    def cell_center(self, r: int, c: int) -> Tuple[int, int]:
        x1, y1, x2, y2 = self.box
        cw = (x2 - x1) / self.size
        ch = (y2 - y1) / self.size
        return int(round(x1 + (c + 0.5) * cw)), int(round(y1 + (r + 0.5) * ch))


class VisionError(RuntimeError):
    pass


def to_array(img: Image.Image) -> np.ndarray:
    return np.asarray(img.convert("RGB"), dtype=np.float32)


# ---------------------------------------------------------------- 1. tahta sınırı

def _fill_gaps(mask: np.ndarray, max_gap: int) -> np.ndarray:
    """Kısa False boşluklarını (tahta çizgileri) doldurur."""
    out = mask.copy()
    idx = np.flatnonzero(mask)
    for a, b in zip(idx[:-1], idx[1:]):
        if 1 < b - a <= max_gap + 1:
            out[a:b] = True
    return out


def _longest_run(mask: np.ndarray, max_gap: int = 0) -> Tuple[int, int]:
    if max_gap:
        mask = _fill_gaps(mask, max_gap)
    best = (0, 0)
    start = None
    for i, v in enumerate(list(mask) + [False]):
        if v and start is None:
            start = i
        elif not v and start is not None:
            if i - start > best[1] - best[0]:
                best = (start, i)
            start = None
    return best


def find_grid_box(arr: np.ndarray, sat_threshold: float = 22.0) -> Box:
    """Renkli hücrelerin oluşturduğu en büyük kare alanı bulur."""
    h, w, _ = arr.shape
    sat = arr.max(axis=2) - arr.min(axis=2)
    colorful = sat > sat_threshold

    # Önce satırlara göre: tahtanın olduğu satırlarda genişliğin büyük kısmı renkli
    row_frac = colorful.mean(axis=1)
    gap = max(2, int(min(h, w) * 0.025))  # çizgi kalınlığı toleransı
    y1, y2 = _longest_run(row_frac > 0.35, gap)
    if y2 - y1 < h * 0.15:
        raise VisionError("Tahta bulunamadı (renkli alan yok). --grid ile elle verin.")

    col_frac = colorful[y1:y2].mean(axis=0)
    x1, x2 = _longest_run(col_frac > 0.35, gap)
    if x2 - x1 < w * 0.3:
        raise VisionError("Tahta bulunamadı (genişlik çok küçük). --grid ile elle verin.")

    # Tahta karedir: uzun kenarı kısa kenara göre kırp (ortalayarak)
    bw, bh = x2 - x1, y2 - y1
    if bh > bw * 1.08:
        # Muhtemelen üst/alt bölümde renkli başka şeyler var -> en kare parçayı ara
        best = None
        for start in range(y1, y2 - bw + 1, max(1, bw // 50)):
            frac = colorful[start:start + bw, x1:x2].mean()
            if best is None or frac > best[0]:
                best = (frac, start)
        y1 = best[1]
        y2 = y1 + bw
    elif bw > bh * 1.08:
        cx = (x1 + x2) // 2
        x1, x2 = cx - bh // 2, cx - bh // 2 + bh
    return _refine_box(arr, (int(x1), int(y1), int(x2), int(y2)))


def _refine_box(arr: np.ndarray, box: Box) -> Box:
    """Renkli alan sınırını, tahtanın dış çerçeve çizgisine kadar genişletir."""
    h, w, _ = arr.shape
    x1, y1, x2, y2 = box
    m = max(3, int((x2 - x1) * 0.03))

    def edges(profile: np.ndarray, lo: int, hi: int, base: int) -> Tuple[int, int]:
        # lo/hi: kaba kenarlar (profil koordinatında). Her iki kenarın ±m çevresindeki
        # en dıştaki güçlü geçişi bul.
        def outer(start: int, end: int, from_left: bool) -> Optional[int]:
            seg = profile[max(0, start):min(len(profile), end)]
            if len(seg) == 0 or seg.max() <= 0:
                return None
            strong = np.flatnonzero(seg > seg.max() * 0.5)
            i = strong[0] if from_left else strong[-1] + 1
            return max(0, start) + int(i)

        a = outer(lo - m, lo + m, True)
        b = outer(hi - m, hi + m, False)
        return (base + (a if a is not None else lo), base + (b if b is not None else hi))

    ex1, ex2 = max(0, x1 - m), min(w, x2 + m)
    ey1, ey2 = max(0, y1 - m), min(h, y2 + m)
    px = _edge_profile(arr[y1:y2, ex1:ex2], axis=1)
    py = _edge_profile(arr[ey1:ey2, x1:x2], axis=0)
    nx1, nx2 = edges(px, x1 - ex1, x2 - ex1, ex1)
    ny1, ny2 = edges(py, y1 - ey1, y2 - ey1, ey1)
    return nx1, ny1, nx2, ny2


# ---------------------------------------------------------------- 2. N tespiti

def _edge_profile(region: np.ndarray, axis: int) -> np.ndarray:
    """Komşu pikseller arası renk farkının, verilen eksen boyunca ortalaması."""
    if axis == 1:  # x yönünde profil
        diff = np.abs(np.diff(region, axis=1)).sum(axis=2)
        return diff.mean(axis=0)
    diff = np.abs(np.diff(region, axis=0)).sum(axis=2)
    return diff.mean(axis=1)


def _score_n(profile: np.ndarray, n: int) -> float:
    length = len(profile) + 1
    step = length / n
    win = max(1, int(step * 0.12))

    def peak(pos: float) -> float:
        p = int(round(pos))
        lo, hi = max(0, p - win - 1), min(len(profile), p + win + 1)
        return float(profile[lo:hi].max()) if hi > lo else 0.0

    lines = [peak(k * step) for k in range(1, n)]
    mids = [peak((k + 0.5) * step) for k in range(0, n)]
    return float(np.mean(lines) - np.mean(mids))


def detect_size(arr: np.ndarray, box: Box, n_min: int = 4, n_max: int = 16) -> int:
    x1, y1, x2, y2 = box
    region = arr[y1:y2, x1:x2]
    px = _edge_profile(region, axis=1)
    py = _edge_profile(region, axis=0)
    scores = {n: _score_n(px, n) + _score_n(py, n) for n in range(n_min, n_max + 1)}
    best = max(scores.values())
    # N=12'lik tahtada N=4 ve 6 da benzer puan alır (çizgileri alt kümesi). Puanı en
    # iyiye yakın olanlar arasında en büyük N'yi seç.
    return max(n for n, s in scores.items() if s >= best * 0.85)


# ---------------------------------------------------------------- 3. bölgeler

def sample_cell_colors(arr: np.ndarray, box: Box, n: int, frac: float = 0.3) -> np.ndarray:
    """Her hücrenin ortasındaki küçük karenin medyan rengi."""
    x1, y1, x2, y2 = box
    cw, ch = (x2 - x1) / n, (y2 - y1) / n
    out = np.zeros((n, n, 3), dtype=np.float32)
    for r in range(n):
        for c in range(n):
            cx, cy = x1 + (c + 0.5) * cw, y1 + (r + 0.5) * ch
            hw, hh = max(1, cw * frac / 2), max(1, ch * frac / 2)
            patch = arr[int(cy - hh):int(cy + hh) + 1, int(cx - hw):int(cx + hw) + 1]
            out[r, c] = np.median(patch.reshape(-1, 3), axis=0)
    return out


def cluster_colors(colors: np.ndarray, k: int) -> List[List[int]]:
    """Hücre renklerini tam k gruba ayırır (ortalama bağlantılı hiyerarşik kümeleme).

    Aynı renkteki komşu hücreler önce birleşir; böylece benzer tonlu iki ayrı
    bölge bile çoğu zaman doğru ayrılır.
    """
    n = colors.shape[0]
    flat = colors.reshape(-1, 3).astype(np.float64)
    m = len(flat)
    dist = np.sqrt(((flat[:, None, :] - flat[None, :, :]) ** 2).sum(axis=2))

    clusters = {i: [i] for i in range(m)}
    # cluster mesafe matrisi (ortalama bağlantı)
    cd = dist.copy()
    np.fill_diagonal(cd, np.inf)
    active = set(range(m))
    while len(active) > k:
        idx = sorted(active)
        sub = cd[np.ix_(idx, idx)]
        a_i, b_i = np.unravel_index(np.argmin(sub), sub.shape)
        a, b = idx[a_i], idx[b_i]
        na, nb = len(clusters[a]), len(clusters[b])
        # a'ya b'yi kat, mesafeleri güncelle
        new_row = (cd[a] * na + cd[b] * nb) / (na + nb)
        cd[a, :] = new_row
        cd[:, a] = new_row
        cd[a, a] = np.inf
        cd[b, :] = np.inf
        cd[:, b] = np.inf
        clusters[a].extend(clusters.pop(b))
        active.discard(b)

    labels = np.zeros(m, dtype=int)
    # Okunabilirlik için: bölge numaralarını sol-üstten itibaren ilk görünüşe göre ver
    order = sorted(clusters.values(), key=min)
    for new_id, members in enumerate(order):
        labels[members] = new_id
    return labels.reshape(n, n).tolist()


# ---------------------------------------------------------------- hepsi bir arada

def read_board(img: Image.Image, box: Optional[Box] = None, size: Optional[int] = None) -> Board:
    arr = to_array(img)
    if box is None:
        box = find_grid_box(arr)
    if size is None:
        size = detect_size(arr, box)
    colors = sample_cell_colors(arr, box, size)
    regions = cluster_colors(colors, size)
    return Board(box=box, size=size, regions=regions, colors=colors)


def draw_debug(img: Image.Image, board: Board, cats=()) -> Image.Image:
    """Tespit edilen tahtayı, bölge numaralarını ve çözümü görsel üzerine çizer."""
    out = img.convert("RGB").copy()
    d = ImageDraw.Draw(out)
    x1, y1, x2, y2 = board.box
    d.rectangle([x1, y1, x2 - 1, y2 - 1], outline=(255, 0, 0), width=4)
    n = board.size
    cw, ch = (x2 - x1) / n, (y2 - y1) / n
    for k in range(1, n):
        d.line([x1 + k * cw, y1, x1 + k * cw, y2], fill=(255, 0, 0), width=1)
        d.line([x1, y1 + k * ch, x2, y1 + k * ch], fill=(255, 0, 0), width=1)
    for r in range(n):
        for c in range(n):
            cx, cy = board.cell_center(r, c)
            d.text((cx - 4, cy - 6), str(board.regions[r][c]), fill=(0, 0, 0))
    for r, c in cats:
        cx, cy = board.cell_center(r, c)
        rad = min(cw, ch) * 0.3
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=(0, 0, 0), width=5)
    return out
