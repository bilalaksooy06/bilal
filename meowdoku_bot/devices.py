"""Ekran görüntüsü alma ve dokunma/tıklama arka uçları.

* AdbDevice     : USB/Wi-Fi ile bağlı Android telefon (veya ADB açık BlueStacks/emülatör)
* DesktopDevice : Bilgisayar ekranı (BlueStacks penceresi, tarayıcı sürümü vb.)
* ImageDevice   : Sadece test için; bir PNG dosyasını "ekran" gibi kullanır
"""

from __future__ import annotations

import io
import shutil
import subprocess
import time
from typing import List, Optional, Tuple

from PIL import Image


class Device:
    def screenshot(self) -> Image.Image:
        raise NotImplementedError

    def tap(self, x: int, y: int) -> None:
        raise NotImplementedError

    def double_tap(self, x: int, y: int) -> None:
        self.tap(x, y)
        self.tap(x, y)


class AdbDevice(Device):
    def __init__(self, serial: Optional[str] = None, adb: str = "adb"):
        if shutil.which(adb) is None:
            raise RuntimeError(
                "'adb' bulunamadı. Android platform-tools kurun: "
                "https://developer.android.com/tools/releases/platform-tools"
            )
        self.base: List[str] = [adb] + (["-s", serial] if serial else [])

    def _run(self, *args: str, capture: bool = False) -> bytes:
        res = subprocess.run(self.base + list(args), capture_output=True, check=False)
        if res.returncode != 0:
            raise RuntimeError(f"adb hatası: {res.stderr.decode(errors='replace').strip()}")
        return res.stdout

    def screenshot(self) -> Image.Image:
        data = self._run("exec-out", "screencap", "-p", capture=True)
        return Image.open(io.BytesIO(data)).convert("RGB")

    def tap(self, x: int, y: int) -> None:
        self._run("shell", "input", "tap", str(x), str(y))

    def double_tap(self, x: int, y: int) -> None:
        # İki dokunuşu tek shell çağrısında, arka arkaya gönder (daha hızlı)
        self._run("shell", f"input tap {x} {y} & input tap {x} {y}; wait")


class DesktopDevice(Device):
    def __init__(self, monitor: int = 1):
        try:
            import mss  # noqa: F401
            import pyautogui
        except ImportError as e:  # pragma: no cover
            raise RuntimeError("Masaüstü modu için: pip install mss pyautogui") from e
        import mss

        self._mss = mss.mss()
        self.mon = self._mss.monitors[monitor]
        self.pg = pyautogui
        self.pg.PAUSE = 0.02
        self.scale: Tuple[float, float] = (1.0, 1.0)

    def screenshot(self) -> Image.Image:
        shot = self._mss.grab(self.mon)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        # Retina/HiDPI ekranlarda ekran görüntüsü pikseli != fare koordinatı
        self.scale = (self.mon["width"] / shot.size[0], self.mon["height"] / shot.size[1])
        return img

    def _to_screen(self, x: int, y: int) -> Tuple[int, int]:
        return (
            int(self.mon["left"] + x * self.scale[0]),
            int(self.mon["top"] + y * self.scale[1]),
        )

    def tap(self, x: int, y: int) -> None:
        self.pg.click(*self._to_screen(x, y))

    def double_tap(self, x: int, y: int) -> None:
        self.pg.doubleClick(*self._to_screen(x, y), interval=0.05)


class ImageDevice(Device):
    """Test/deneme: verilen görseli ekran sayar, dokunuşları sadece kaydeder."""

    def __init__(self, path: str):
        self.img = Image.open(path).convert("RGB")
        self.taps: List[Tuple[int, int]] = []

    def screenshot(self) -> Image.Image:
        return self.img

    def tap(self, x: int, y: int) -> None:
        self.taps.append((x, y))


def place_cat(dev: Device, x: int, y: int, mode: str, delay: float) -> None:
    """Bir hücreye kedi koyar.

    mode:
      double : çift dokunma (mobil uygulamada varsayılan)
      single : tek dokunma kedi koyuyorsa
      twice  : iki ayrı dokunuş (1. = X işareti, 2. = kedi) — aralarında `delay` kadar bekler
      long   : uzun basma (bazı sürümlerde)
    """
    if mode == "double":
        dev.double_tap(x, y)
    elif mode == "single":
        dev.tap(x, y)
    elif mode == "twice":
        dev.tap(x, y)
        time.sleep(delay)
        dev.tap(x, y)
    elif mode == "long":
        if isinstance(dev, AdbDevice):
            dev._run("shell", "input", "swipe", str(x), str(y), str(x), str(y), "600")
        elif isinstance(dev, DesktopDevice):
            sx, sy = dev._to_screen(x, y)
            dev.pg.mouseDown(sx, sy)
            time.sleep(0.6)
            dev.pg.mouseUp(sx, sy)
        else:
            dev.tap(x, y)
    else:
        raise ValueError(f"Bilinmeyen dokunma modu: {mode}")
