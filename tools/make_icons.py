"""Crea le icone dell'app dal marchio UBIQUINT (lo stesso SVG della favicon della web app) con Chromium (Playwright).
Uso:  python3 tools/make_icons.py     (scrive app/res/mipmap-*/ic_launcher.png e ic_launcher_fg.png)"""
import pathlib
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "res"
MARK = ('<path d="M42 18Q42 26 42 34L42 50A8 8 0 0 0 58 50L58 34Q58 26 58 18" fill="none" stroke="#96f4ff" stroke-width="8" stroke-linecap="round"/>'
        '<path d="M33 18Q33 26 33 34L33 47.5A17 17 0 0 0 67 47.5L67 34Q67 26 67 18" fill="none" stroke="#c9b8ff" stroke-width="6.5" stroke-linecap="round"/>'
        '<path d="M24 18Q24 26 24 34L24 45A26 26 0 0 0 76 45L76 34Q76 26 76 18" fill="none" stroke="#c9b8ff" stroke-width="6.5" stroke-linecap="round"/>'
        '<path d="M15 18Q15 26 15 34L15 42.5A35 35 0 0 0 85 42.5L85 34Q85 26 85 18" fill="none" stroke="#c9b8ff" stroke-width="6.5" stroke-linecap="round"/>')
# icona classica: identica alla favicon (quadrato arrotondato scuro + marchio)
LEGACY = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="#0b0818"/>'
          '<g transform="translate(9 8) scale(.82)">' + MARK + '</g></svg>')
# icona adattiva (Android 8+): primo piano 108x108 con il marchio dentro il cerchio sicuro di 66 unità; lo sfondo è @color/icon_bg
S = 0.61
FG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
      f'<g transform="translate({54 - 50 * S:.3f} {54 - 47.75 * S:.3f}) scale({S})">' + MARK + '</g></svg>')
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def render(page, svg: str, px: int, out: pathlib.Path) -> None:
    page.set_viewport_size({"width": px, "height": px})
    sized = svg.replace("<svg ", f'<svg width="{px}" height="{px}" ', 1)
    page.set_content(f'<html><body style="margin:0;background:transparent"><div style="width:{px}px;height:{px}px">{sized}</div></body></html>')
    out.parent.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(out), omit_background=True, clip={"x": 0, "y": 0, "width": px, "height": px})


def main() -> None:
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(device_scale_factor=1)
        for d, f in DENS.items():
            render(page, LEGACY, round(48 * f), RES / f"mipmap-{d}" / "ic_launcher.png")
            render(page, FG, round(108 * f), RES / f"mipmap-{d}" / "ic_launcher_fg.png")
        b.close()
    ad = RES / "mipmap-anydpi-v26"
    ad.mkdir(parents=True, exist_ok=True)
    (ad / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/icon_bg" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_fg" />\n'
        '</adaptive-icon>\n', encoding="utf-8")
    print("icone create in", RES)


if __name__ == "__main__":
    main()
