#!/bin/zsh
# Renders the Reality Office app icon (tools/icons/app-icon.svg) to the launcher's foreground PNG.
set -e
cd "${0:A:h}/../.."
rsvg-convert -w 1424 -h 1424 tools/icons/app-icon.svg -o app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
echo "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
