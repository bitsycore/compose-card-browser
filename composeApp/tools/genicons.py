"""
Generates `AppIcons.kt` from the handful of Material icons this app actually draws.

Run from the repository root, pointing at the sources jar Gradle has already downloaded:

    python composeApp/tools/genicons.py ~/.gradle/caches/.../material-icons-extended-1.7.3-sources.jar

Reading that jar rather than Material Symbols is the point: the path data comes out byte-identical
to what the library would have drawn, so the 24 icons generated here cannot drift from the 13 still
coming from `material-icons-core`. Nothing is redrawn by hand and nothing is fetched over the
network.
"""
import io
import re
import sys
import zipfile

SOURCES_JAR = sys.argv[1]
OUT = "composeApp/src/commonMain/kotlin/com/bitsycore/cardbrowser/ui/common/AppIcons.kt"

# The extended-only icons, as `variant/Name`. The other thirteen this app uses are in
# material-icons-core, which Material3 already brings, and keep using `Icons.*`.
WANTED = [
    ("outlined", "ArrowDownward"),
    ("outlined", "ArrowUpward"),
    ("outlined", "BrokenImage"),
    ("outlined", "ChevronLeft"),
    ("outlined", "ChevronRight"),
    ("outlined", "CloudDownload"),
    ("outlined", "CloudOff"),
    ("outlined", "Description"),
    ("outlined", "Download"),
    ("outlined", "DownloadDone"),
    ("outlined", "DragHandle"),
    ("outlined", "ErrorOutline"),
    ("outlined", "FilterList"),
    ("outlined", "GridView"),
    ("outlined", "Inbox"),
    ("outlined", "SearchOff"),
    ("outlined", "StarBorder"),
    ("outlined", "Storage"),
    ("outlined", "Style"),
    ("outlined", "TravelExplore"),
    ("outlined", "Tune"),
    ("outlined", "Visibility"),
    ("outlined", "VisibilityOff"),
    ("automirrored/outlined", "OpenInNew"),
]

HEADER = '''package com.bitsycore.cardbrowser.ui.common

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons this app uses that `material-icons-core` does not carry.
 *
 * ## Why these are here rather than depended on
 *
 * `material-icons-extended` is every Material icon there is -- a **36 MB** desktop jar and an
 * **18 MB** klib for each iOS target -- and this app draws 24 of them. R8 strips the rest from an
 * Android release build; nothing strips them from a desktop distributable or an iOS binary, which
 * is where the weight was being paid.
 *
 * ## Where they came from
 *
 * Generated from that library's own sources jar by `composeApp/tools/genicons.py`, so the path data is exactly
 * what it would have drawn -- not redrawn by hand and not re-exported from Material Symbols, either
 * of which risks a shape that differs by a pixel from the thirteen icons still coming from
 * `material-icons-core`. Those thirteen are still `Icons.Outlined.*`; mixing the two sources is
 * fine precisely because both are the same drawings.
 *
 * Apache 2.0, © The Android Open Source Project, like the library they come from.
 *
 * ## Adding one
 *
 * Add its name to `WANTED` in the generator and re-run it against the sources jar. Do not paste
 * path data in by hand.
 */
object AppIcons {
'''


def main():
    with zipfile.ZipFile(SOURCES_JAR) as jar:
        out = [HEADER]
        for variant, icon in WANTED:
            path = f"commonMain/androidx/compose/material/icons/{variant}/{icon}.kt"
            if path not in jar.namelist():
                raise SystemExit(f"not in the jar: {path}")
            src = jar.read(path).decode("utf-8")
            body = convert(src, icon, variant)
            out.append(body)
    out.append("}\n")
    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print(f"wrote {OUT}: {len(WANTED)} icons")


def convert(src, icon, variant):
    """Rewrites one generated icon onto the `AppIcons` receiver."""
    # The property, from `public val Icons.X.Name: ImageVector` to the end of the file.
    start = src.index("public val Icons.")
    prop = src[start:].strip()
    receiver = "Icons.AutoMirrored.Outlined" if "automirrored" in variant else "Icons.Outlined"
    prop = prop.replace(f"public val {receiver}.{icon}:", f"val {icon}:", 1)
    # The library names its vectors after the receiver it hung them on; keep the name honest.
    prop = prop.replace(f'materialIcon(name = "', 'materialIcon(name = "AppIcons.', 1)
    prop = prop.replace(f'"AppIcons.Outlined.{icon}"', f'"AppIcons.{icon}"')
    prop = prop.replace(f'"AppIcons.AutoMirrored.Outlined.{icon}"', f'"AppIcons.{icon}"')
    # Four-space indent to tabs, which is what this codebase uses.
    prop = re.sub(r"^( +)", lambda m: "\t" * (len(m.group(1)) // 4), prop, flags=re.M)
    # Members of the object, not extensions on it. An extension property has to be imported by
    # name, which would put two dozen icon imports in every file that draws one.
    prop = "\n".join(("\t" + line) if line.strip() else line for line in prop.split("\n"))
    return prop + "\n"


main()
