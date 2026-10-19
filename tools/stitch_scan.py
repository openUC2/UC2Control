#!/usr/bin/env python3
"""Register and stitch a UC2 Control phone scan with ImSwitch's ShitScope pipeline.

The phone writes the ShitScope scan layout (scan.json, tiles.json, tiles/) with
JPEG tiles; ImSwitch reads TIFF tiles. This converts the tiles once, then runs
the same registration and stitching as ShitScope's "Re-analyse last scan".

    python stitch_scan.py <scan folder or the shared .zip> [--full]

Run it with a Python where ImSwitch imports (e.g. ImSwitch's .venv); it also
needs Pillow. Writes scan_quality.json and stitched.tif (2x downsampled) into
the scan folder; --full adds stitched_full.tif at full resolution.
"""
import json
import os
import sys
import zipfile

import numpy as np
import tifffile
from PIL import Image

from imswitch.imcontrol.model import shitscope_scan


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    scan = argv[1].rstrip("/")
    if scan.lower().endswith(".zip"):
        # The app zips <name>/... as <name>.zip: unpack next to it.
        with zipfile.ZipFile(scan) as z:
            z.extractall(os.path.dirname(os.path.abspath(scan)))
        scan = scan[:-4]

    index = os.path.join(scan, "tiles.json")
    with open(index) as fh:
        records = json.load(fh)
    for r in records:
        if r["file"].lower().endswith((".jpg", ".jpeg")):
            tif = os.path.splitext(r["file"])[0] + ".tif"
            tifffile.imwrite(os.path.join(scan, tif), np.asarray(Image.open(os.path.join(scan, r["file"]))))
            r["file"] = tif
    with open(index, "w") as fh:
        json.dump(records, fh, indent=1)

    result = shitscope_scan.analyse_saved_scan(scan)
    print(json.dumps(result["summary"], indent=1))
    if "--full" in argv:
        print(shitscope_scan.export_full_res(scan))


if __name__ == "__main__":
    main(sys.argv)
