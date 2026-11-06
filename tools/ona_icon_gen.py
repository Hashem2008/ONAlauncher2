# ONA pixel-art icon generator: writes Android vector drawables + preview PNGs
import random, sys, os
from collections import defaultdict
RES = sys.argv[1]
OUT = sys.argv[2]

FONT = {
'O': [".#####.","#######","##...##","##...##","##...##","##...##","##...##","#######",".#####."],
'N': ["##...##","###..##","####.##","#######","##.####","##..###","##...##","##...##","##...##"],
'A': [".#####.","#######","##...##","##...##","#######","#######","##...##","##...##","##...##"],
}
def fmt(v):
    s = ("%.3f" % v).rstrip('0').rstrip('.')
    return s if s else "0"

class Layer:
    def __init__(self): self.rects = defaultdict(list)  # color -> [(x,y,w,h)]
    def px(self, color, x, y, w, h): self.rects[color].append((x, y, w, h))

def letters(layer, ox, oy, u, mono=None):
    text = "ONA"
    mask = set()
    cx = 0
    for ch in text:
        g = FONT[ch]
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v == '#': mask.add((cx + c, r))
        cx += 8
    W = cx - 1
    # drop shadow
    if mono is None:
        for (x, y) in mask:
            for dx, dy in ((1, 1), (0, 1), (1, 0)):
                p = (x + dx, y + dy)
                if p not in mask:
                    layer.px("#170E05", ox + p[0] * u, oy + p[1] * u, u, u)
    for (x, y) in mask:
        if mono is not None:
            col = mono
        elif (x, y - 1) not in mask:
            col = "#FFE39A"
        elif (x, y + 1) not in mask:
            col = "#A86F1C"
        elif (x - 1, y) not in mask:
            col = "#F6C65A"
        elif (x + 1, y) not in mask:
            col = "#C98E2C"
        else:
            col = "#E6AE42"
        layer.px(col, ox + x * u, oy + y * u, u, u)
    return W

def pickaxe(layer, ox, oy, u, mirror=False):
    head = [(1,3),(1,2),(2,1),(3,0),(4,0),(5,0),(6,0),(7,1),(8,1),(9,2),(9,3),(10,4),(10,5)]
    head_dark = [(2,2),(3,1),(8,2),(9,4)]
    handle = [(i, 10 - i) for i in range(0, 8)]
    def put(col, pts):
        for x, y in pts:
            if mirror: x = 10 - x
            layer.px(col, ox + x * u, oy + y * u, u, u)
    put("#5A3A1A", [(x + 1, y) for x, y in handle[:7]])
    put("#9C6B36", handle)
    put("#2BB3A7", head_dark)
    put("#6FF0E2", head)

def sword(layer, ox, oy, u, mirror=False):
    blade = [(10 - i, i) for i in range(0, 7)]
    edge = [(10 - i, i + 1) for i in range(0, 6)]
    guard = [(1,5),(2,6),(4,8),(5,9),(3,7)]
    grip = [(2,8),(1,9)]
    def put(col, pts):
        for x, y in pts:
            if mirror: x = 10 - x
            layer.px(col, ox + x * u, oy + y * u, u, u)
    put("#8E9AA6", edge)
    put("#EEF3F7", blade)
    put("#6FF0E2", guard)
    put("#9C6B36", grip)
    put("#E6AE42", [(0,10)])

def compass(layer, ox, oy, u):
    rows = ["..#####..",".#######.","#########","#########","#########","#########","#########",".#######.","..#####.."]
    mask = {(c, r) for r, row in enumerate(rows) for c, v in enumerate(row) if v == '#'}
    for (x, y) in mask:
        edge = any((x+dx, y+dy) not in mask for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)))
        col = "#C98E2C" if edge else "#EDE4C8"
        if edge and (y <= 1 or x <= 1): col = "#F6C65A"
        layer.px(col, ox + x * u, oy + y * u, u, u)
    for p, col in (((5,2),"#D9342B"),((5,3),"#D9342B"),((4,4),"#2B2B2B"),((3,5),"#6B6B6B"),((3,6),"#6B6B6B")):
        layer.px(col, ox + p[0] * u, oy + p[1] * u, u, u)

def background(layer, size=108, tile=6):
    rnd = random.Random(7)
    layer.px("#0F1722", 0, 0, size, size)
    shades = ["#121C2A", "#0C131D", "#142133", "#101926"]
    n = size // tile
    for ty in range(n):
        for tx in range(n):
            r = rnd.random()
            if r < 0.45: continue
            layer.px(shades[int(r * 100) % 4], tx * tile, ty * tile, tile, tile)
    # faint map lines (dotted)
    for i in range(0, size, 4):
        layer.px("#1B2A3E", i, 30, 2, 1)
        layer.px("#1B2A3E", 78, i, 1, 2)
    # gold specks
    for _ in range(14):
        x, y = rnd.randrange(4, size - 4), rnd.randrange(4, size - 4)
        layer.px("#D4A640", x, y, 1.5, 1.5)

def foreground(layer, mono=None):
    u = 2.75
    W = 23
    ox = (108 - W * u) / 2
    oy = 46
    letters(layer, ox, oy, u, mono)
    if mono is None:
        pickaxe(layer, 43, 20, 2, mirror=False)
        sword(layer, 43, 20, 2, mirror=True)
        compass(layer, 54 - 4.5 * 2, 75, 2)

def vector(layers, path, comment, clip=None):
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- %s -->' % comment,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    ind = "    "
    if clip:
        out.append('    <group>')
        out.append('        <clip-path android:pathData="%s" />' % clip)
        ind = "        "
    for layer in layers:
        for col, rects in layer.rects.items():
            d = "".join("M%s,%sh%sv%sh-%sz" % (fmt(x), fmt(y), fmt(w), fmt(h), fmt(w)) for x, y, w, h in rects)
            out.append('%s<path android:fillColor="%s" android:pathData="%s" />' % (ind, col, d))
    if clip: out.append('    </group>')
    out.append('</vector>')
    open(path, "w").write("\n".join(out) + "\n")

def png(layers, path, px, round_mask=None):
    from PIL import Image, ImageDraw
    s = px / 108
    im = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for layer in layers:
        for col, rects in layer.rects.items():
            for x, y, w, h in rects:
                d.rectangle([x * s, y * s, (x + w) * s - 1, (y + h) * s - 1], fill=col)
    if round_mask:
        m = Image.new("L", (px, px), 0)
        md = ImageDraw.Draw(m)
        if round_mask == "circle": md.ellipse([0, 0, px - 1, px - 1], fill=255)
        else: md.rounded_rectangle([0, 0, px - 1, px - 1], radius=px * 0.22, fill=255)
        im.putalpha(m)
    im.save(path)

bg, fg, mono = Layer(), Layer(), Layer()
background(bg); foreground(fg); foreground(mono, mono="#FFFFFF")
D = os.path.join(RES, "drawable")
vector([bg], os.path.join(D, "ic_launcher_background_ona.xml"), "ONA icon background: night map in pixel blocks")
vector([fg], os.path.join(D, "ic_launcher_foreground.xml"), "ONA icon: gold pixel letters, pickaxe, sword and compass")
vector([mono], os.path.join(D, "ic_launcher_monochrome.xml"), "ONA icon (themed): the letters")
vector([bg, fg], os.path.join(D, "img_launcher.xml"), "ONA logo inside the app",
       clip="M24,0h60a24,24 0,0 1,24 24v60a24,24 0,0 1,-24 24h-60a24,24 0,0 1,-24 -24v-60a24,24 0,0 1,24 -24z")
# legacy mipmaps (the visible 72/108 crop of the adaptive icon)
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
from PIL import Image
big = os.path.join(OUT, "full.png")
png([bg, fg], big, 1080)
full = Image.open(big)
crop = full.crop((180, 180, 900, 900))
for k, v in sizes.items():
    for name, shape in (("ic_launcher", "rect"), ("ic_launcher_round", "circle")):
        img = crop.resize((v, v), Image.NEAREST)
        from PIL import ImageDraw
        m = Image.new("L", (v, v), 0); md = ImageDraw.Draw(m)
        if shape == "circle": md.ellipse([0, 0, v - 1, v - 1], fill=255)
        else: md.rounded_rectangle([0, 0, v - 1, v - 1], radius=v * 0.2, fill=255)
        img.putalpha(m)
        img.save(os.path.join(RES, "mipmap-" + k, name + ".webp"), "WEBP", lossless=True)
png([bg, fg], os.path.join(OUT, "preview.png"), 432, round_mask="rect")
print("ok")
