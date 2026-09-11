"""
Shapes the desktop app icon: rounds its corners and insets it in its own box.

    python composeApp/tools/shape_app_icon.py

Reads `app-icon-source-512.png`, which is the artwork as drawn -- a full-bleed square -- and writes
the two files the desktop build actually uses:

  * `app-icon-512.png`, the window and taskbar icon, and the Linux app image's icon
  * `app-icon.ico`, which is the same thing at the seven sizes Windows picks between

Both are generated, so neither should be edited by hand. The source is the only file to replace if
the artwork ever changes.

## Why the icon is not the artwork

A full-bleed square is what the artwork is, and it is the wrong shape for a taskbar. Every other
icon beside it -- Windows 11's own, and anything shipped in the last five years -- is inset in its
box and has its corners taken off, so a square that runs edge to edge does not read as bolder, it
reads as a placeholder that nobody finished.

The two numbers below are deliberately restrained. This is the "tiny rounded corner" end of the
scale rather than a squircle: the artwork is a card, cards have corners, and rounding them to a
pebble would be drawing something else.
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RESOURCES = os.path.join(HERE, "..", "src", "desktopMain", "resources")

SOURCE = os.path.join(RESOURCES, "app-icon-source-512.png")
PNG_OUT = os.path.join(RESOURCES, "app-icon-512.png")
ICO_OUT = os.path.join(RESOURCES, "app-icon.ico")

# How much of the box the artwork occupies, by how big the box is.
#
# Not one number, and the difference is visible rather than theoretical. 88% is right at 128px and
# up: it leaves the margin Windows' own icons have, so this does not touch its neighbours in a
# taskbar. At 16px it is ruinous -- the artwork is a tilted card with a diamond on it, 12% of the
# pixels is most of what makes that readable, and inset it turns to porridge.
#
# So the margin is spent where there are pixels to spare. This is what icon kits do by hand when
# they ship a simplified glyph for the small sizes; there is one piece of artwork here, so the next
# best thing is to stop taking anything away from it.
SCALE_BY_SIZE = [
	(24, 1.00),   # 16 and 24: every pixel, corners only
	(64, 0.94),   # 32 to 64: a hairline of margin
	(10 ** 9, 0.88),  # 128 and up: the full margin
]

# Corner radius, as a fraction of the *artwork* rather than of the box. 12% takes the point off the
# corner at a glance without reading as a rounded rectangle.
RADIUS = 0.12

# The sizes handed to AWT for the window and the taskbar.
#
# More than one on purpose. Given a single image, AWT scales it itself with a filter far worse than
# the one here -- a 512px icon squeezed to 24px for a title bar is where "why does it look fuzzy?"
# comes from. Given a list, it picks the nearest and scales barely at all.
WINDOW_SIZES = [16, 24, 32, 48, 64, 128, 256, 512]

# What Windows picks between. 256 is what it uses for large tiles and 16 for the title bar; the
# sizes in between are there so it never has to scale one of those down itself.
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]

# Supersampling for the corner mask. Pillow's rounded rectangle is not antialiased, so it is drawn
# four times too big and scaled down, which is where the smooth edge comes from.
OVERSAMPLE = 4


def rounded(image, radius_fraction):
	"""Returns `image` with its corners rounded, as a new RGBA image."""
	vSize = image.size[0]
	vRadius = int(vSize * radius_fraction)
	vMask = Image.new("L", (vSize * OVERSAMPLE, vSize * OVERSAMPLE), 0)
	ImageDraw.Draw(vMask).rounded_rectangle(
		[(0, 0), (vSize * OVERSAMPLE - 1, vSize * OVERSAMPLE - 1)],
		radius=vRadius * OVERSAMPLE,
		fill=255,
	)
	vMask = vMask.resize((vSize, vSize), Image.LANCZOS)

	vOut = image.convert("RGBA")
	# Multiplied into whatever alpha the artwork already has rather than replacing it, so a source
	# that is not a solid square keeps its own transparency.
	vAlpha = vOut.getchannel("A").point(lambda vValue: vValue)
	vOut.putalpha(Image.composite(vAlpha, Image.new("L", vOut.size, 0), vMask))
	return vOut


def scale_for(box):
	"""How much of a `box`-pixel icon the artwork should fill."""
	for vLimit, vScale in SCALE_BY_SIZE:
		if box <= vLimit:
			return vScale
	raise AssertionError("SCALE_BY_SIZE must end with a catch-all")


def shaped(box):
	"""The finished icon at `box` pixels square: artwork inset in a transparent box, corners off."""
	vArtwork = int(box * scale_for(box))
	vSource = Image.open(SOURCE).convert("RGBA").resize((vArtwork, vArtwork), Image.LANCZOS)
	vCanvas = Image.new("RGBA", (box, box), (0, 0, 0, 0))
	vOffset = (box - vArtwork) // 2
	vCanvas.paste(rounded(vSource, RADIUS), (vOffset, vOffset))
	return vCanvas


def main():
	if not os.path.exists(SOURCE):
		raise SystemExit(
			"no %s -- the full-bleed artwork is the input to this, not its output" % SOURCE,
		)
	shaped(512).save(PNG_OUT)
	for vSize in WINDOW_SIZES:
		if vSize != 512:
			shaped(vSize).save(os.path.join(RESOURCES, "app-icon-%d.png" % vSize))
	# Pillow builds the multi-size .ico itself, but only by downscaling the one image it is given.
	# Each size is shaped separately instead, so the inset and the radius are the same fraction of
	# every one rather than 16px inheriting a radius drawn for 256.
	vFrames = [shaped(vSize) for vSize in ICO_SIZES]
	vFrames[-1].save(ICO_OUT, format="ICO", sizes=[(vSize, vSize) for vSize in ICO_SIZES],
	                 append_images=vFrames[:-1])
	print("wrote %s and %s" % (os.path.basename(PNG_OUT), os.path.basename(ICO_OUT)))


if __name__ == "__main__":
	main()
