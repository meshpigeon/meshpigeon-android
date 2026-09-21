#!/usr/bin/env bash
# Regenerate MeshPigeon brand assets from the committed vector mark
# (scripts/dove-mark.svg — traced from the Noto Color Emoji dove U+1F54A,
# olive branch removed; see 01-naming.md). Requires ImageMagick and
# rsvg-convert. Outputs are committed; only rerun when changing the mark.
#
# Usage:
#   scripts/gen-brand-assets.sh
#   BG="#3D7EB5" scripts/gen-brand-assets.sh   # different background

set -euo pipefail
cd "$(dirname "$0")/.."

BG="${BG:-#356F8C}"        # icon / logo / banner background
MARK=scripts/dove-mark.svg
SIL=scripts/dove-silhouette.svg
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Tight master raster renders (the SVG viewBox hugs the dove).
rsvg-convert -w 640 "$MARK" -o "$WORK/mark.png"
rsvg-convert -w 640 "$SIL" -o "$WORK/sil.png"

# --- Android launcher + notification layers ----------------------------------
center() { # src maxdim size out — mark centered on a transparent canvas
    magick -size "$3x$3" xc:none \( "$1" -resize "$2x$2\>" \) \
        -gravity center -composite "$4"
}

layer() { # name src scale — one drawable-* PNG per density (canvas = 108dp grid)
    local name=$1 src=$2 scale=$3 pair res_dir max
    for pair in "mdpi 108" "hdpi 162" "xhdpi 216" "xxhdpi 324" "xxxhdpi 432"; do
        set -- $pair
        res_dir="app/src/main/res/drawable-$1"
        mkdir -p "$res_dir"
        max=$(awk -v c=$2 -v s=$scale 'BEGIN{printf "%d", c*s}')
        center "$src" "$max" "$2" "$res_dir/$name.png"
    done
}

layer ic_launcher_foreground "$WORK/mark.png" 0.62   # fits the 66dp safe zone
layer ic_launcher_monochrome "$WORK/sil.png" 0.62    # Android 13 themed icon
# Notification small icon: 24dp grid, mark at 80%.
for pair in "mdpi 24" "hdpi 36" "xhdpi 48" "xxhdpi 72" "xxxhdpi 96"; do
    set -- $pair
    res_dir="app/src/main/res/drawable-$1"
    mkdir -p "$res_dir"
    center "$WORK/sil.png" "$(( $2 * 8 / 10 ))" "$2" "$res_dir/ic_notification.png"
done

# --- repo logo + banners -----------------------------------------------------
# Square repo logo (512): mark on the brand background.
magick -size 512x512 xc:"$BG" \( "$WORK/mark.png" -resize '318x318>' \) \
    -gravity center -composite logo.png

# Wide banner: dove + stacked title/tagline, the group centered.
banner() { # out width height marksize
    local out=$1 w=$2 h=$3 m=$4
    local tfont=$((m / 2)) gfont=$((m * 17 / 100))
    magick -background none \
        pango:"<span font=\"DejaVu Sans Bold ${tfont}\" foreground=\"white\">MeshPigeon</span>" \
        "$WORK/title.png"
    magick -background none \
        pango:"<span font=\"DejaVu Sans ${gfont}\" foreground=\"#B9C9D8\">Messages that find their way home.</span>" \
        "$WORK/tag.png"
    magick "$WORK/title.png" "$WORK/tag.png" -background none -append "$WORK/block.png"
    local bw bh gap total left
    bw=$(magick identify -format '%w' "$WORK/block.png")
    bh=$(magick identify -format '%h' "$WORK/block.png")
    gap=$((m / 5))
    total=$(( m + gap + bw ))
    # shrink the text block until the group fits
    while [ "$total" -gt "$w" ]; do
        tfont=$(( tfont * (w - m - gap) / (total - m - gap) ))
        gfont=$(( gfont * (w - m - gap) / (total - m - gap) ))
        magick -background none \
            pango:"<span font=\"DejaVu Sans Bold ${tfont}\" foreground=\"white\">MeshPigeon</span>" \
            "$WORK/title.png"
        magick -background none \
            pango:"<span font=\"DejaVu Sans ${gfont}\" foreground=\"#B9C9D8\">Messages that find their way home.</span>" \
            "$WORK/tag.png"
        magick "$WORK/title.png" "$WORK/tag.png" -background none -append "$WORK/block.png"
        bw=$(magick identify -format '%w' "$WORK/block.png")
        bh=$(magick identify -format '%h' "$WORK/block.png")
        total=$(( m + gap + bw ))
    done
    left=$(( (w - total) / 2 ))
    magick -size "${w}x${h}" xc:"$BG" \
        \( "$WORK/mark.png" -resize "${m}x${m}>" \) \
            -gravity northwest -geometry "+${left}+$(( (h - m) / 2 ))" -composite \
        \( "$WORK/block.png" \) \
            -gravity northwest -geometry "+$(( left + m + gap ))+$(( (h - bh) / 2 ))" -composite \
        "$out"
}

if [ -d ../plans/meshpigeon ]; then
    mkdir -p ../plans/meshpigeon/assets
    banner ../plans/meshpigeon/assets/social-preview.png 1280 640 300
    magick -size 48x48 xc:"$BG" \( "$WORK/mark.png" -resize '34x34>' \) \
        -gravity center -composite ../plans/meshpigeon/assets/favicon.png
fi
banner banner.png 1200 300 220

echo "done — launcher/notification layers, logo.png regenerated (BG=$BG)"
echo "banner.png + plans assets written next to the repo root"