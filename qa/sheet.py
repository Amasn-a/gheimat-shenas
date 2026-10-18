"""Contact sheet of QA screenshots: python3 qa/sheet.py <dir> <out.png> [cols] [width]"""
import sys, os
from PIL import Image, ImageDraw
d, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 4
w = int(sys.argv[4]) if len(sys.argv) > 4 else 300
files = sorted(f for f in os.listdir(d) if f.endswith('.png'))
ims = [Image.open(os.path.join(d, f)).convert('RGB') for f in files]
h = int(w * ims[0].height / ims[0].width)
rows = (len(ims) + cols - 1) // cols
S = Image.new('RGB', (cols * (w + 10) + 10, rows * (h + 34) + 10), 'white')
dr = ImageDraw.Draw(S)
for i, (f, im) in enumerate(zip(files, ims)):
    x, y = 10 + (i % cols) * (w + 10), 10 + (i // cols) * (h + 34)
    S.paste(im.resize((w, h)), (x, y + 24))
    dr.text((x, y + 6), f[:-4], fill='black')
S.save(out)
print(out, S.size)
