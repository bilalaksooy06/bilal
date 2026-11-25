"""Komut satırı: python -m meowdoku_bot --help"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .devices import AdbDevice, DesktopDevice, ImageDevice, place_cat
from .solver import format_board, solve
from .vision import VisionError, draw_debug, read_board


def parse_xy(text: str):
    parts = [int(float(p)) for p in text.replace(" ", "").split(",")]
    return parts


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="meowdoku_bot",
        description="Meowdoku'yu ekran görüntüsünden okuyup çözer ve kendi kendine oynar.",
    )
    p.add_argument("--device", choices=["adb", "desktop", "image"], default="adb",
                   help="adb: Android telefon/emülatör, desktop: bilgisayar ekranı, image: PNG ile deneme")
    p.add_argument("--serial", help="Birden fazla ADB cihazı varsa seri numarası")
    p.add_argument("--monitor", type=int, default=1, help="Masaüstü modunda ekran numarası")
    p.add_argument("--image", help="--device image için ekran görüntüsü dosyası")

    p.add_argument("--grid", help="Tahta sınırı elle: x1,y1,x2,y2 (ekran görüntüsü pikseli)")
    p.add_argument("--size", type=int, help="Tahta boyutu N elle (örn. 8)")

    p.add_argument("--tap-mode", choices=["double", "single", "twice", "long"], default="double",
                   help="Kedi koyma hareketi (varsayılan: double)")
    p.add_argument("--tap-delay", type=float, default=0.15,
                   help="'twice' modunda iki dokunuş arası bekleme (sn)")
    p.add_argument("--move-delay", type=float, default=0.25, help="İki kedi arası bekleme (sn)")

    p.add_argument("--dry-run", action="store_true", help="Sadece çöz ve göster, dokunma")
    p.add_argument("--debug", metavar="DOSYA.png", help="Tespit edilen tahtayı bu dosyaya çiz")

    p.add_argument("--loop", action="store_true", help="Seviye bitince sonrakine geç ve devam et")
    p.add_argument("--next", action="append", default=[], metavar="X,Y",
                   help="Seviye bitince sırayla dokunulacak nokta(lar) ('Sonraki' butonu). Birden çok verilebilir")
    p.add_argument("--after-solve", type=float, default=2.5,
                   help="Son kediden sonra kazanma ekranı için bekleme (sn)")
    p.add_argument("--after-next", type=float, default=2.0, help="'Sonraki'ye bastıktan sonra bekleme (sn)")
    p.add_argument("--max-levels", type=int, default=0, help="Döngüde en fazla kaç seviye (0 = sınırsız)")
    return p


def make_device(args):
    if args.device == "adb":
        return AdbDevice(args.serial)
    if args.device == "desktop":
        return DesktopDevice(args.monitor)
    if not args.image:
        sys.exit("--device image için --image DOSYA.png verin")
    return ImageDevice(args.image)


def play_once(dev, args, last_regions=None):
    """Tek seviye oynar. Dönüş: çözülen tahtanın bölgeleri (veya None)."""
    img = dev.screenshot()
    box = tuple(parse_xy(args.grid)) if args.grid else None
    try:
        board = read_board(img, box=box, size=args.size)
    except VisionError as e:
        print(f"[!] {e}")
        return None

    print(f"[+] Tahta: {board.size}x{board.size}, konum {board.box}")
    if last_regions is not None and board.regions == last_regions:
        print("[=] Tahta önceki seviyeyle aynı, yeni seviye bekleniyor...")
        return None

    try:
        cats = solve(board.regions)
    except ValueError as e:
        cats = None
        print(f"[!] {e}")

    if args.debug:
        draw_debug(img, board, cats or ()).save(args.debug)
        print(f"[i] Tespit görseli: {args.debug}")

    print(format_board(board.regions, cats or ()))
    if not cats:
        print("[!] Çözüm bulunamadı — renkler yanlış okunmuş olabilir. --debug ile kontrol edin,"
              " gerekirse --grid / --size verin.")
        return None

    print(f"[+] Çözüm: {cats}")
    if args.dry_run:
        return board.regions

    for r, c in cats:
        x, y = board.cell_center(r, c)
        place_cat(dev, x, y, args.tap_mode, args.tap_delay)
        time.sleep(args.move_delay)
    print("[+] Tüm kediler yerleştirildi.")
    return board.regions


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    dev = make_device(args)

    if not args.loop:
        return 0 if play_once(dev, args) else 1

    solved = 0
    last = None
    fails = 0
    print("[i] Döngü modu — durdurmak için Ctrl+C")
    try:
        while not args.max_levels or solved < args.max_levels:
            regions = play_once(dev, args, last)
            if regions is None:
                fails += 1
                if fails >= 10:
                    print("[!] Üst üste 10 kez tahta okunamadı, duruluyor.")
                    return 1
                for xy in args.next:  # belki kazanma ekranında takıldı
                    dev.tap(*parse_xy(xy))
                    time.sleep(0.5)
                time.sleep(args.after_next)
                continue
            fails = 0
            last = regions
            solved += 1
            print(f"[✓] Çözülen seviye: {solved}")
            time.sleep(args.after_solve)
            for xy in args.next:
                dev.tap(*parse_xy(xy))
                time.sleep(0.6)
            time.sleep(args.after_next)
    except KeyboardInterrupt:
        print(f"\n[i] Durduruldu. Toplam çözülen seviye: {solved}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
