"""
Genera las imágenes de la marca UYPS para Android a partir de los SVG de design/:

  - Ícono de la app (adaptive icon): mipmap-*/ic_launcher_foreground.png
  - Spinner: drawable-nodpi/spinner_base.png (fondo fijo) y spinner_ring.png (anillo que gira)
  - design/uyps-logo.png (512 px, para el README)

Usa Chrome/Edge en modo headless para dibujar los SVG (tipografías y brillo incluidos).
Uso:  python tools/render_brand.py
"""
import os
import re
import struct
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, "design")
RES = os.path.join(ROOT, "app", "src", "main", "res")
BROWSERS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/google-chrome", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]
BROWSER = next(b for b in BROWSERS if os.path.exists(b))


def svg_inner(path):
    s = open(path, encoding="utf-8").read()
    return re.sub(r"^.*?<svg[^>]*>|</svg>\s*$", "", s, flags=re.S)


def render(svg_body, view_box, size, out, inner_box=None):
    """Dibuja svg_body (contenido de un SVG 0..512) en un PNG size×size transparente.
    inner_box = (x, y, w) en unidades de 108 para ubicar el dibujo dentro de un lienzo mayor."""
    if inner_box:
        x, y, w = inner_box
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">'
               f'<svg x="{x}" y="{y}" width="{w}" height="{w}" viewBox="{view_box}">{svg_body}</svg></svg>')
    else:
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
               f'viewBox="{view_box}">{svg_body}</svg>')
    html = ('<!doctype html><html><head><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}'
            f'svg{{display:block}}</style></head><body>{svg}</body></html>')
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
        f.write(html)
        tmp = f.name
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run([BROWSER, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--default-background-color=00000000", f"--window-size={size},{size}",
                    f"--screenshot={out}", "file:///" + tmp.replace("\\", "/")],
                   check=True, capture_output=True, timeout=60)
    os.unlink(tmp)
    w, h = struct.unpack(">II", open(out, "rb").read()[16:24])
    assert (w, h) == (size, size), f"{out}: {w}x{h} en vez de {size}"
    print(f"  {os.path.relpath(out, ROOT)}  {w}x{h}")


def main():
    logo = svg_inner(os.path.join(DESIGN, "uyps-logo.svg"))
    spinner = svg_inner(os.path.join(DESIGN, "uyps-spinner.svg"))

    # Ícono adaptativo: lienzo de 108 dp, el logo ocupa ~80 dp centrado (zona visible ~72 dp)
    for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        render(logo, "0 0 512 512", px, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"),
               inner_box=(14, 14, 80))
    render(logo, "0 0 512 512", 512, os.path.join(DESIGN, "uyps-logo.png"))

    # Spinner en dos capas: el anillo (con su pin) gira, el resto queda fijo
    m = re.search(r"<g>\s*<animateTransform[^>]*/>(.*?)</g>\s*(?=<path)", spinner, flags=re.S)
    ring_body = m.group(1)
    defs = re.search(r"<defs>.*?</defs>", spinner, flags=re.S).group(0)
    base = spinner.replace(m.group(0), "")
    render(base, "0 0 512 512", 512, os.path.join(RES, "drawable-nodpi", "spinner_base.png"))
    render(defs + ring_body, "0 0 512 512", 512, os.path.join(RES, "drawable-nodpi", "spinner_ring.png"))
    print("listo")


if __name__ == "__main__":
    main()
