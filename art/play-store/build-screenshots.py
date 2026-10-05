"""
Build the Play listing screenshots from raw device captures.

Deliberately a script. The previous set was made by hand in September and was wrong within ten
days -- it showed an Actions tab and a Timeline tab, neither of which exists any more -- because
redoing it was an afternoon's work and so it never got redone. This makes a refresh one command.

The listing is map-free on purpose: a Coverage screenshot shows the streets around wherever the
phone was standing, and a store listing is published to everybody, permanently.
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920
BG_TOP, BG_BOTTOM = (10, 12, 18), (6, 7, 11)
TEXT, DIM, ACCENT = (242, 244, 248), (150, 157, 172), (90, 170, 255)

BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
REG = "/System/Library/Fonts/Supplemental/Arial.ttf"

SHOTS = [
    # The heading has to match the capture, not the pitch. This frame reads SINR 6 dB, which is
    # not "nothing works" -- but the three disagreeing bar counts are right there and are the
    # honest headline for it.
    ("screenshots-0.7/raw/1-now.png", "Three bar counts.\nOne phone.",
     "Carrier, Android and chipset disagree about the same instant."),
    ("screenshots-0.7/raw/2-mast.png", "It isn't you.",
     "What this mast has done before, at this hour"),
    ("screenshots-0.7/raw/3-diagnosis.png", "What is wrong,\nand what you can do",
     "Measured on your phone, never guessed"),
    ("screenshots-0.7/raw/4-data.png", "It all stays\non your phone",
     "Take a copy, or destroy it"),
]


def canvas():
    img = Image.new("RGB", (W, H), BG_TOP)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(
            int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)))
    return img


def rounded(im, r):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], r, fill=255)
    out = Image.new("RGBA", im.size)
    out.paste(im, (0, 0), mask)
    return out


def build(src, title, sub, out):
    img = canvas()
    d = ImageDraw.Draw(img)

    y = 92
    for i, line in enumerate(title.split("\n")):
        f = ImageFont.truetype(BOLD, 62)
        # The second line carries the accent, so the claim reads as one sentence with its
        # sharp end coloured rather than two competing headlines.
        d.text((64, y), line, font=f, fill=TEXT if i == 0 else ACCENT)
        y += 74
    y += 10
    # Refuse rather than overflow. A subtitle four pixels too wide runs off the edge and nothing
    # says so until somebody looks at the finished image -- which is how a listing ends up wrong.
    sf = ImageFont.truetype(REG, 31)
    assert sf.getlength(sub) <= W - 128, f"subtitle too wide ({int(sf.getlength(sub))}px): {sub}"
    for line in title.split("\n"):
        tf = ImageFont.truetype(BOLD, 62)
        assert tf.getlength(line) <= W - 128, f"title too wide: {line}"
    d.text((64, y), sub, font=sf, fill=DIM)

    phone = Image.open(src).convert("RGB")
    pw = 712
    ph = int(phone.height * pw / phone.width)
    phone = rounded(phone.resize((pw, ph), Image.LANCZOS), 34)

    px, py = (W - pw) // 2, 372
    # A hairline edge so the dark screenshot does not dissolve into the dark background.
    ImageDraw.Draw(img).rounded_rectangle(
        [px - 2, py - 2, px + pw + 1, py + ph + 1], 36, outline=(46, 52, 66), width=3)
    img.paste(phone, (px, py), phone)
    img.save(out, quality=95)
    print(f"  {out}  {img.size[0]}x{img.size[1]}")


import os
os.makedirs("screenshots-0.7", exist_ok=True)
print("built:")
for i, (src, title, sub) in enumerate(SHOTS, 1):
    build(src, title, sub, f"screenshots-0.7/{i}-{os.path.basename(src).split("-",1)[1]}".replace(".png", ".jpg"))
