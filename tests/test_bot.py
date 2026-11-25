import time

import pytest

from meowdoku_bot.__main__ import main
from meowdoku_bot.solver import is_valid_solution, solve
from meowdoku_bot.vision import read_board

from synthetic import random_puzzle, render


def same_partition(a, b):
    """İki bölge haritası etiketler hariç aynı mı?"""
    mapping = {}
    for ra, rb in zip(a, b):
        for x, y in zip(ra, rb):
            if mapping.setdefault(x, y) != y:
                return False
    return len(set(mapping.values())) == len(mapping)


@pytest.mark.parametrize("n", [5, 6, 7, 8, 9, 10, 11, 12])
@pytest.mark.parametrize("seed", range(5))
def test_solver(n, seed):
    regions, _ = random_puzzle(n, seed)
    t = time.perf_counter()
    cats = solve(regions)
    assert time.perf_counter() - t < 2.0
    assert cats is not None and is_valid_solution(regions, cats)


def test_unsolvable():
    # 0 ve 1 numaralı bölgeler aynı satıra sıkışmış -> o satırda 2 kedi gerekir, çözümsüz
    regions = [
        [0, 0, 1, 1],
        [2, 2, 2, 2],
        [3, 3, 3, 3],
        [3, 3, 3, 3],
    ]
    assert solve(regions) is None


@pytest.mark.parametrize("n", [5, 6, 7, 8, 9, 10, 11, 12])
@pytest.mark.parametrize("seed", range(3))
def test_vision_auto(n, seed):
    regions, _ = random_puzzle(n, seed + 100)
    img, box = render(regions)
    board = read_board(img)
    assert board.size == n
    for got, exp in zip(board.box, box):
        assert abs(got - exp) <= 12
    assert same_partition(board.regions, regions)


def test_end_to_end_image_device(tmp_path, capsys):
    regions, _ = random_puzzle(8, 7)
    img, _ = render(regions)
    path = tmp_path / "screen.png"
    img.save(path)
    rc = main(["--device", "image", "--image", str(path), "--debug", str(tmp_path / "dbg.png")])
    assert rc == 0
    assert (tmp_path / "dbg.png").exists()
    assert "Tüm kediler yerleştirildi" in capsys.readouterr().out


@pytest.mark.parametrize("style", [
    dict(line=0, border=6),                                   # sadece bölge sınırları
    dict(line=2, border=4, width=720, height=1600, top=400, margin=30),  # küçük ekran
    dict(line=1, border=10, width=1440, height=3200, top=900, margin=80),  # büyük ekran
])
@pytest.mark.parametrize("n", [6, 8, 10])
def test_vision_styles(style, n):
    regions, _ = random_puzzle(n, 42 + n)
    img, _ = render(regions, **style)
    board = read_board(img)
    assert board.size == n
    assert same_partition(board.regions, regions)
