#!/bin/sh
# Copies the ODMR WebSerial app (openUC2/TechnicalDocs-openUC2-QBox,
# Production_Files/Software/ODMR_Server/webserial-app) into the app's assets,
# unchanged. The ODMR tab shows this copy; run this after the webapp changes.
#
#   tools/sync_odmr_webapp.sh [path/to/webserial-app]
set -eu
SRC=${1:-../TechnicalDocs-openUC2-QBox/Production_Files/Software/ODMR_Server/webserial-app}
DST=$(dirname "$0")/../app/src/main/assets/odmr
[ -f "$SRC/index.html" ] || { echo "no webserial-app at $SRC" >&2; exit 1; }
rm -rf "$DST"
mkdir -p "$DST"
cp "$SRC/index.html" "$DST/"
cp -R "$SRC/assets" "$DST/"
echo "copied $SRC -> $DST"
