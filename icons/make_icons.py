#!/usr/bin/env python3
"""Generates TheCatGame icon art (SVG) and renders every PNG the app and PWA need.

Art: a black cat peeking up from the bottom edge, eyes locked on a glowing laser dot.
Rendering uses headless Chrome (SVG -> PNG) and ImageMagick (resizing).

Usage: python3 icons/make_icons.py
"""
import os
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "icons")
RES = os.path.join(ROOT, "app", "res")
WEB = os.path.join(ROOT, "cloudflare", "site", "public")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

DEFS = """
<defs>
  <radialGradient id="bg" cx="72%" cy="18%" r="100%">
    <stop offset="0" stop-color="#43308a"/>
    <stop offset=".5" stop-color="#1d1440"/>
    <stop offset="1" stop-color="#0a0716"/>
  </radialGradient>
  <radialGradient id="glow">
    <stop offset="0" stop-color="#ff2d55" stop-opacity=".85"/>
    <stop offset=".3" stop-color="#ff2d55" stop-opacity=".3"/>
    <stop offset="1" stop-color="#ff2d55" stop-opacity="0"/>
  </radialGradient>
  <radialGradient id="dot" cx="40%" cy="38%" r="65%">
    <stop offset="0" stop-color="#ff9db3"/>
    <stop offset=".45" stop-color="#ff2d55"/>
    <stop offset="1" stop-color="#d1123c"/>
  </radialGradient>
  <radialGradient id="eye" cx="45%" cy="35%" r="70%">
    <stop offset="0" stop-color="#fbff9e"/>
    <stop offset="1" stop-color="#9fd62b"/>
  </radialGradient>
  <linearGradient id="fur" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#211e2e"/>
    <stop offset="1" stop-color="#0b0a11"/>
  </linearGradient>
  <linearGradient id="rim" x1="1" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#ff6f8f"/>
    <stop offset=".5" stop-color="#7a5cd6"/>
    <stop offset="1" stop-color="#7a5cd6" stop-opacity="0"/>
  </linearGradient>
</defs>
"""

# Cat drawn in a 512x512 space; the body runs off the bottom edge.
CAT = """
<g>
  <path d="M92 540 C 78 420, 98 338, 122 300 L 132 156 Q 136 132 154 146 L 222 230
           Q 256 220 290 230 L 358 146 Q 376 132 380 156 L 390 300
           C 414 338, 434 420, 420 540 Z"
        fill="url(#fur)" stroke="url(#rim)" stroke-width="6" stroke-linejoin="round"/>
  <path d="M148 178 L 152 262 L 206 236 Z" fill="#ff8fb0" opacity=".28"/>
  <path d="M364 178 L 360 262 L 306 236 Z" fill="#ff8fb0" opacity=".28"/>

  <ellipse cx="196" cy="350" rx="42" ry="46" fill="url(#eye)"/>
  <ellipse cx="316" cy="350" rx="42" ry="46" fill="url(#eye)"/>
  <ellipse cx="209" cy="337" rx="15" ry="31" fill="#0b0a11"/>
  <ellipse cx="329" cy="337" rx="15" ry="31" fill="#0b0a11"/>
  <circle cx="190" cy="330" r="9" fill="#fff" opacity=".9"/>
  <circle cx="310" cy="330" r="9" fill="#fff" opacity=".9"/>

  <path d="M242 410 h28 l-14 14 z" fill="#ff8fb0"/>
  <path d="M256 424 q -9 15 -24 10 M256 424 q 9 15 24 10" stroke="#4a4466" stroke-width="4"
        fill="none" stroke-linecap="round"/>
  <g stroke="#a9a3c8" stroke-opacity=".55" stroke-width="3" stroke-linecap="round">
    <path d="M160 418 L 70 400"/><path d="M160 432 L 72 446"/>
    <path d="M352 418 L 442 400"/><path d="M352 432 L 440 446"/>
  </g>
</g>
"""

def laser(cx, cy, s=1.0):
    return f"""
<g>
  <circle cx="{cx}" cy="{cy}" r="{100*s}" fill="url(#glow)"/>
  <circle cx="{cx}" cy="{cy}" r="{24*s}" fill="url(#dot)"/>
  <circle cx="{cx-8*s}" cy="{cy-8*s}" r="{7*s}" fill="#fff" opacity=".85"/>
</g>"""

TRAIL = """<path d="M64 236 Q 196 52 352 122" stroke="#ff2d55" stroke-opacity=".4" stroke-width="7"
      stroke-dasharray="1 18" stroke-linecap="round" fill="none"/>"""

def square_svg(rounded):
    clip = '<clipPath id="r"><rect width="512" height="512" rx="112"/></clipPath>' if rounded else ""
    open_g = '<g clip-path="url(#r)">' if rounded else "<g>"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
{DEFS.replace('</defs>', clip + '</defs>')}
{open_g}
  <rect width="512" height="512" fill="url(#bg)"/>
  {TRAIL}
  {laser(380, 122)}
  {CAT}
</g>
</svg>"""

def banner_svg():
    # 320x180 TV banner, drawn at 2x.
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
{DEFS}
<rect width="640" height="360" fill="url(#bg)"/>
<path d="M40 150 Q 150 30 252 62" stroke="#ff2d55" stroke-opacity=".4" stroke-width="5"
      stroke-dasharray="1 14" stroke-linecap="round" fill="none"/>
{laser(262, 66, 0.85)}
<g transform="translate(-6 76) scale(.56)">{CAT}</g>
<g font-family="ui-rounded, 'SF Pro Rounded', -apple-system, Helvetica, sans-serif" fill="#fff">
  <text x="318" y="196" font-size="50" font-weight="800" letter-spacing="-1">
    <tspan fill="#b9acf0" font-weight="600">The</tspan>Cat<tspan fill="#ff4d6d">Game</tspan>
  </text>
  <text x="321" y="236" font-size="21" font-weight="600" fill="#9a93bd" letter-spacing="2">LASER PLAY FOR CATS</text>
</g>
</svg>"""

def render(svg, w, h, out):
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg)
        path = f.name
    subprocess.run([CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
                    f"--window-size={w},{h}", "--default-background-color=00000000",
                    f"--screenshot={out}", "file://" + path],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.unlink(path)

def resize(src, size, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run(["magick", src, "-filter", "Lanczos", "-resize", size, "-strip", out], check=True)

def main():
    tmp = tempfile.mkdtemp()
    for name, svg in [("icon.svg", square_svg(True)), ("maskable.svg", square_svg(False)),
                      ("banner.svg", banner_svg())]:
        with open(os.path.join(ICONS, name), "w") as f:
            f.write(svg)

    icon = os.path.join(tmp, "icon.png")
    mask = os.path.join(tmp, "maskable.png")
    banner = os.path.join(tmp, "banner.png")
    render(square_svg(True), 512, 512, icon)
    render(square_svg(False), 512, 512, mask)
    render(banner_svg(), 640, 360, banner)

    # Android launcher icon + TV banner.
    for d, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        resize(icon, f"{px}x{px}", os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"))
    resize(banner, "320x180", os.path.join(RES, "drawable-xhdpi", "banner.png"))
    resize(banner, "480x270", os.path.join(RES, "drawable-xxhdpi", "banner.png"))

    # PWA / web icons.
    web = os.path.join(WEB, "icons")
    resize(icon, "192x192", os.path.join(web, "icon-192.png"))
    resize(icon, "512x512", os.path.join(web, "icon-512.png"))
    resize(mask, "512x512", os.path.join(web, "maskable-512.png"))
    resize(mask, "192x192", os.path.join(web, "maskable-192.png"))
    resize(mask, "180x180", os.path.join(web, "apple-touch-icon.png"))
    resize(icon, "32x32", os.path.join(web, "favicon-32.png"))

    # Previews kept next to the sources.
    resize(icon, "512x512", os.path.join(ICONS, "preview-icon.png"))
    resize(banner, "640x360", os.path.join(ICONS, "preview-banner.png"))
    print("Icons written.")

if __name__ == "__main__":
    main()
