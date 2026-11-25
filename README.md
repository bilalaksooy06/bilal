# Meowdoku Bot 🐱

Meowdoku'yu kendi kendine oynayan bot. Ekranın görüntüsünü alır, tahtayı okur,
bulmacayı çözer ve kedileri senin yerine yerleştirir. Döngü modunda seviye
bitince bir sonrakine geçip oynamaya devam eder.

## Nasıl çalışır?

1. **Ekran görüntüsü**: telefondan ADB ile ya da bilgisayar ekranından alınır.
2. **Görüntü işleme** (`meowdoku_bot/vision.py`): renkli kare tahta bulunur,
   çizgilerin aralığından boyut (N×N) hesaplanır, her hücrenin rengi okunup
   renkler N bölgeye ayrılır.
3. **Çözücü** (`meowdoku_bot/solver.py`): Meowdoku kurallarına göre çözer:
   - her satırda, her sütunda ve her renkli bölgede tam 1 kedi
   - kediler birbirine değemez (çapraz dahil)
4. **Dokunma** (`meowdoku_bot/devices.py`): çözümdeki hücrelere dokunur.

## Kurulum

Python 3.9+ gerekir.

```bash
pip install -r requirements.txt
```

### A) Android telefon (önerilen)

1. Telefonda **Geliştirici seçenekleri**ni aç (Ayarlar → Telefon hakkında →
   *Yapım numarası*na 7 kez dokun).
2. Geliştirici seçeneklerinde **USB hata ayıklama**yı aç.
   (Bazı Xiaomi telefonlarda ayrıca **"USB hata ayıklama (Güvenlik ayarları)"**
   açılmalıdır, yoksa dokunma çalışmaz.)
3. Bilgisayara [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
   kur ve telefonu USB ile bağla. `adb devices` komutu telefonu göstermeli.
4. Oyunu aç, bir seviyeye gir (tahta boş görünsün).

```bash
python -m meowdoku_bot --device adb --dry-run --debug tespit.png
```

`--dry-run` sadece çözer, dokunmaz. `tespit.png` dosyasını aç: kırmızı çerçeve
tahtanın üstüne oturuyor ve hücrelerdeki numaralar renklerle uyuşuyorsa hazırsın:

```bash
python -m meowdoku_bot --device adb
```

### B) Bilgisayar (BlueStacks / emülatör / tarayıcı sürümü)

```bash
pip install mss pyautogui
python -m meowdoku_bot --device desktop --dry-run --debug tespit.png
python -m meowdoku_bot --device desktop
```

Oyun penceresi ekranda açık ve önde olmalı. Fareyi ekranın sol üst köşesine
götürmek pyautogui'yi acil durdurur. BlueStacks'te ADB'yi açarsan
(Ayarlar → Gelişmiş → Android Debug Bridge) A yöntemi de çalışır:
`adb connect 127.0.0.1:5555`.

> **iPhone**: iOS'ta ekranı dışarıdan kontrol etmek çok zor. En kolayı
> oyunu bilgisayarda (tarayıcı sürümü / Mac'te iPad uygulaması) açıp B yöntemini kullanmak.

## Kedi nasıl konuyor? (`--tap-mode`)

Oyunun sürümüne göre kedi koyma hareketi farklı olabilir:

| Mod | Ne yapar |
|---|---|
| `double` (varsayılan) | Hücreye çift dokunur |
| `twice` | İki ayrı dokunuş (1. dokunuş ✗, 2. dokunuş kedi koyuyorsa) |
| `single` | Tek dokunuş |
| `long` | Uzun basma |

ADB ile çift dokunma bazı telefonlarda yavaş kalıp iki tek dokunuş gibi
algılanabilir. Kediler yerine ✗ çıkıyorsa `--tap-mode twice` dene.

## Kendi kendine oynasın: döngü modu

Seviye bitince çıkan **Sonraki / Devam** butonunun ekran koordinatını bul
(`--debug` görüntüsünü bir resim programında açıp fareyi butonun üzerine getirince
koordinat görünür; telefonda Geliştirici seçenekleri → *İşaretçi konumu* da gösterir).

```bash
python -m meowdoku_bot --device adb --loop --next 540,1900
```

- Birden fazla butona sırayla basmak gerekiyorsa `--next` birden çok kez verilebilir:
  `--next 540,1900 --next 540,2100` (örn. önce "Devam", sonra reklam kapatma).
- `--max-levels 20` → 20 seviye sonra durur. `Ctrl+C` her zaman durdurur.
- Bekleme süreleri: `--after-solve`, `--after-next`, `--move-delay`.

## Otomatik tespit tutmazsa

- `--grid x1,y1,x2,y2`: tahtanın sol üst ve sağ alt köşesi (ekran görüntüsü pikseli)
- `--size 8`: tahta boyutu
- `--debug tespit.png`: botun ne gördüğünü kontrol etmek için

Örnek: `python -m meowdoku_bot --device adb --grid 60,650,1020,1610 --size 8`

Kendi ekran görüntünle denemek için (dokunma yapmaz):

```bash
python -m meowdoku_bot --device image --image ekran.png --debug tespit.png
```

## Testler

```bash
pip install pytest
python -m pytest tests
```

Testler rastgele (çözümü garantili) tahtalar üretip telefon ekranı gibi çizer,
botun bunları doğru okuyup çözdüğünü kontrol eder.
