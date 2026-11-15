# Draws the Scam Shield icon (blue shield, white check) into static/icons/.
# Run: python3 dev/make-icons.py   (needs Pillow)
from PIL import Image, ImageDraw

S = 1024  # draw large, downscale for clean edges
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Shield: flat top with rounded shoulders, sides curving to a point.
top, shoulder, bottom, left, right, mid = 80, 200, 950, 130, 894, S // 2
pts = [(left, shoulder), (mid, top), (right, shoulder)]
for i in range(41):  # right side down to the point (quadratic curve)
    t = i / 40
    x = (1 - t) ** 2 * right + 2 * (1 - t) * t * right + t ** 2 * mid
    y = (1 - t) ** 2 * shoulder + 2 * (1 - t) * t * 700 + t ** 2 * bottom
    pts.append((x, y))
for i in range(41):  # left side back up
    t = i / 40
    x = (1 - t) ** 2 * mid + 2 * (1 - t) * t * left + t ** 2 * left
    y = (1 - t) ** 2 * bottom + 2 * (1 - t) * t * 700 + t ** 2 * shoulder
    pts.append((x, y))
d.polygon(pts, fill=(43, 74, 139, 255))

# Check mark.
d.line([(330, 520), (460, 650), (700, 380)], fill="white", width=110, joint="curve")
for x, y in [(330, 520), (700, 380)]:
    d.ellipse([x - 55, y - 55, x + 55, y + 55], fill="white")

for size in (16, 32, 48, 128):
    img.resize((size, size), Image.LANCZOS).save(f"static/icons/icon{size}.png")
print("wrote static/icons/icon{16,32,48,128}.png")
