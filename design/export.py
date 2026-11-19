#!/usr/bin/env python3
"""Publish the FeurStagram identity from the portable SVG artboards."""

import argparse
from io import BytesIO
import json
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from xml.sax.saxutils import escape
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from fontTools.misc.transform import Identity, Transform
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.svgLib.path import SVGPath
from PIL import Image
from resvg_py import svg_to_bytes

DESIGN = Path(__file__).resolve().parent
ROOT = DESIGN.parent
SVG = DESIGN / "svg"
SKETCH = DESIGN / "sketch/FeurStagram.sketch"
WEB = ROOT / "docs/assets/brand"
CAPTURES = ROOT / "docs/assets/img"
SCREENSHOT_BOARDS = {
    "readme-screenshots": "screenshots.png",
    "readme-settings": "settings-screenshots.png",
}
VARIANTS = tuple(
    f"{shape}-{tone}"
    for shape in ("symbol", "wordmark", "horizontal", "square")
    for tone in ("ink", "paper")
)
ARTBOARDS = VARIANTS + (
    "readme-banner", *SCREENSHOT_BOARDS, "open-graph", "github-social", "favicon",
    "app-icon", "profile-mark", "android-legacy", "android-foreground", "android-monochrome",
)
SWATCHES = ("ink", "paper", "surface", "hover", "line", "secondary", "muted")
SVG_NS = "{http://www.w3.org/2000/svg}"
XLINK_NS = "http://www.w3.org/1999/xlink"
XLINK = f"{{{XLINK_NS}}}href"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def normalized_svg(path):
    """Keep native shapes and images, and remove the optional Sketch artboard wrappers."""
    ET.register_namespace("", SVG_NS[1:-1])
    ET.register_namespace("xlink", XLINK_NS)
    root = ET.parse(path).getroot()
    for group in root.iter(SVG_NS + "g"):
        outline = group.attrib.pop(XLINK, None)
        if outline:
            for child in list(group):
                if child.tag == SVG_NS + "use" and child.attrib == {XLINK: outline}:
                    group.remove(child)
    # Preserve any real masks/uses, but omit unreferenced artboard outlines in defs.
    for defs in list(root.findall(SVG_NS + "defs")):
        for child in list(defs):
            reference = "#" + child.get("id", "")
            if len(reference) > 1 and not any(
                value in (reference, f"url({reference})")
                for node in root.iter()
                for value in node.attrib.values()
            ):
                defs.remove(child)
        if not len(defs):
            root.remove(defs)
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def from_sketch(directory):
    default = "/Applications/Sketch.app/Contents/MacOS/sketchtool"
    sketchtool = os.environ.get("SKETCHTOOL", default)
    with ZipFile(SKETCH) as bundle:
        meta = json.loads(bundle.read("meta.json"))
    ids = {
        board["name"]: key
        for page in meta["pagesAndArtboards"].values()
        for key, board in page["artboards"].items()
    }
    missing = sorted(set(ARTBOARDS) - ids.keys())
    if missing:
        raise ValueError("Plans de travail Sketch manquants : " + ", ".join(missing))
    subprocess.run(
        [sketchtool, "export", "artboards", str(SKETCH), "--formats=svg", "--trimmed=NO",
         "--output=" + str(directory), "--items=" + ",".join(ids[name] for name in ARTBOARDS)],
        check=True,
    )
    return {name: normalized_svg(directory / f"{name}.svg") for name in ARTBOARDS}


def palette():
    with ZipFile(SKETCH) as bundle:
        swatches = json.loads(bundle.read("document.json"))["sharedSwatches"]["objects"]
    colors = {}
    for swatch in swatches:
        name, value = swatch["name"], swatch["value"]
        if name in SWATCHES:
            channels = [round(value[channel] * 255) for channel in ("red", "green", "blue")]
            if len(set(channels)) != 1 or value["alpha"] != 1:
                raise ValueError(f"Variable hors palette monochrome : {name}")
            colors[name] = "#" + "".join(f"{channel:02x}" for channel in channels)
    missing = set(SWATCHES) - colors.keys()
    if missing:
        raise ValueError("Variables Sketch manquantes : " + ", ".join(sorted(missing)))
    return colors


def render(svg, width=None):
    return svg_to_bytes(svg_string=svg.decode(), width=width, skip_system_fonts=True)


def png_image(svg, width=None):
    return Image.open(BytesIO(render(svg, width)))


def capture_thumbnail(path):
    buffer = BytesIO()
    with Image.open(path) as capture:
        capture.thumbnail((540, 1205), Image.Resampling.LANCZOS)
        capture.save(buffer, format="WEBP", quality=88)
    return buffer.getvalue()


def zip_archive(files):
    buffer = BytesIO()
    with ZipFile(buffer, "w") as archive:
        for name, data in sorted(files.items()):
            info = ZipInfo(name)
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)
    return buffer.getvalue()


def number(value):
    return f"{value:.5f}".rstrip("0").rstrip(".") or "0"


def transform(value):
    result = Identity
    for kind, arguments in re.findall(r"(matrix|scale|translate)\(([^)]+)\)", value):
        args = list(map(float, re.split(r"[\s,]+", arguments.strip())))
        if kind == "matrix":
            result = result.transform(Transform(*args))
        elif kind == "scale":
            result = result.scale(*args)
        else:
            result = result.translate(*args)
    remainder = re.sub(r"(?:matrix|scale|translate)\([^)]+\)", "", value)
    if remainder.strip(" ,"):
        raise ValueError(f"Transformation non prise en charge pour le symbole : {value}")
    return result


def mark_outline(svg, pen):
    """Flatten SVG groups for the Android vector and Canvas, with no runtime SVG parser."""
    def visit(node, parent_transform):
        tag = node.tag.removeprefix(SVG_NS)
        combined = parent_transform.transform(transform(node.get("transform", "")))
        if tag in ("svg", "g"):
            for child in node:
                visit(child, combined)
        elif tag in ("path", "polygon", "circle", "ellipse", "rect"):
            shape = ET.Element(node.tag, {k: v for k, v in node.attrib.items() if k != "transform"})
            SVGPath.fromstring(ET.tostring(shape), transform=combined).draw(pen)
        elif tag not in ("title", "desc"):
            raise ValueError(f"Élément non pris en charge pour le symbole : {tag}")

    visit(ET.fromstring(svg), Identity)


def vector_xml(color, path):
    return (
        f'<vector xmlns:android="{ANDROID_NS}"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        f'    <path android:fillColor="{color}" android:fillType="evenOdd"\n'
        f'        android:pathData="{escape(path)}"/>\n'
        '</vector>\n'
    ).encode()


def android_archive(vectors, colors):
    files = {
        f"res/mipmap-{density}/ic_launcher.png": render(vectors["android-legacy"], size)
        for density, size in DENSITIES.items()
    }
    for variant, color in (("foreground", colors["paper"]), ("monochrome", colors["ink"])):
        pen = SVGPathPen(None, ntos=number)
        mark_outline(vectors["android-" + variant], pen)
        files[f"res/drawable/ic_launcher_{variant}.xml"] = vector_xml(color, pen.getCommands())
    files["res/drawable/ic_launcher_background.xml"] = vector_xml(
        colors["ink"], "M0 0H108V108H0Z",
    )
    files["res/mipmap-anydpi-v26/ic_launcher.xml"] = (
        f'<adaptive-icon xmlns:android="{ANDROID_NS}">\n'
        '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
        '</adaptive-icon>\n'
    ).encode()
    return zip_archive(files)


def press_archive(vectors, screenshots):
    files = {
        f"logos/svg/feurstagram-{variant}.svg": vectors[variant] for variant in VARIANTS
    }
    files.update({
        f"logos/png/feurstagram-{variant}.png": render(vectors[variant], 1200)
        for variant in VARIANTS
    })
    files["presentation/open-graph.png"] = render(vectors["open-graph"])
    files.update({f"presentation/{name}.png": data for name, data in screenshots.items()})
    files["brand-guidelines.md"] = (DESIGN / "guidelines/brand-guidelines.md").read_bytes()
    return zip_archive(files)


def favicon_ico(svg):
    sizes = ((16, 16), (32, 32), (48, 48))
    images = [png_image(svg, width).convert("RGBA") for width, _ in sizes]
    buffer = BytesIO()
    images[-1].save(buffer, format="ICO", sizes=sizes, append_images=images[:-1])
    return buffer.getvalue()


def favicon_svg(svg, colors):
    root = ET.fromstring(svg)
    ink, paper = colors["ink"], colors["paper"]
    style = ET.Element(SVG_NS + "style")
    style.text = (
        "@media (prefers-color-scheme: dark) {"
        f' [fill="{ink}" i] {{ fill: {paper}; }}'
        f' [fill="{paper}" i] {{ fill: {ink}; }}'
        " }"
    )
    root.insert(1, style)
    ET.register_namespace("", SVG_NS[1:-1])
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def outputs(vectors, colors):
    declarations = "\n".join(f"    --brand-{name}: {colors[name]};" for name in SWATCHES)
    css = (
        "/* Generated by design/export.py. */\n"
        "@font-face {\n"
        '    font-family: Inter;\n'
        '    src: url("inter-variable.woff2") format("woff2");\n'
        "    font-style: normal;\n    font-weight: 100 900;\n    font-display: swap;\n"
        "}\n"
        f":root {{\n{declarations}\n}}\n"
    )
    screenshots = {name: render(vectors[name]) for name in SCREENSHOT_BOARDS}
    thumbnails = {
        CAPTURES / f"{name}-540.webp": capture_thumbnail(CAPTURES / f"{name}.png")
        for name in ("after", "blocked-surfaces", "unlock-timer")
    }
    return {
        **thumbnails,
        **{WEB / filename: screenshots[name] for name, filename in SCREENSHOT_BOARDS.items()},
        WEB / "tokens.css": css.encode(),
        WEB / "inter-OFL.txt": (DESIGN / "fonts/OFL.txt").read_bytes(),
        WEB / "inter-variable.woff2": (DESIGN / "fonts/InterVariable.woff2").read_bytes(),
        WEB / "horizontal-paper.svg": vectors["horizontal-paper"],
        WEB / "app-icon.svg": vectors["app-icon"],
        WEB / "app-icon-256.png": render(vectors["app-icon"], 256),
        WEB / "apple-touch-icon.png": render(vectors["app-icon"], 180),
        WEB / "favicon.svg": favicon_svg(vectors["favicon"], colors),
        WEB / "favicon.ico": favicon_ico(vectors["favicon"]),
        WEB / "readme-banner.png": render(vectors["readme-banner"]),
        WEB / "open-graph.png": render(vectors["open-graph"]),
        WEB / "feurstagram-press-kit.zip": press_archive(vectors, screenshots),
        ROOT / "assets/social-preview.png": render(vectors["github-social"]),
        DESIGN / "android-launcher.zip": android_archive(vectors, colors),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-sketch", action="store_true", help="refresh SVG masters with Sketch")
    parser.add_argument("--check", action="store_true", help="verify without writing any files")
    args = parser.parse_args()
    if args.from_sketch:
        with TemporaryDirectory(prefix="feurstagram-sketch-") as directory:
            vectors = from_sketch(Path(directory))
    else:
        vectors = {name: (SVG / f"{name}.svg").read_bytes() for name in ARTBOARDS}
    for name, source in vectors.items():
        if ET.fromstring(source).find(".//" + SVG_NS + "text") is not None:
            raise ValueError(f"{name}.svg contient du texte non vectorisé")
    published = {SVG / f"{name}.svg": source for name, source in vectors.items()}
    published.update(outputs(vectors, palette()))
    changed = [path for path, data in published.items() if not path.is_file() or path.read_bytes() != data]
    for path in changed:
        print(f"{'À régénérer' if args.check else 'Exporté'} {path.relative_to(ROOT)}")
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(published[path])
    if not changed:
        print(f"{len(published)} fichiers à jour.")
    return bool(changed) if args.check else False


if __name__ == "__main__":
    raise SystemExit(main())
