"""Descarga edificios y contornos de Overture Maps para el recuadro de Uruguay (sin usar el catálogo STAC)."""
import os
import sys

import pyarrow.parquet as pq
from overturemaps import core

RELEASE = sys.argv[1] if len(sys.argv) > 1 else "2026-11-18.0"
BBOX = (-58.50, -35.10, -53.05, -30.05)
DB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data-build")

for kind, out in (("division_area", "ov_division_area.parquet"), ("building", "ov_buildings.parquet")):
    reader = core.record_batch_reader(kind, bbox=BBOX, release=RELEASE, stac=False)
    path = os.path.join(DB, out)
    writer, n = None, 0
    for batch in reader:
        if writer is None:
            writer = pq.ParquetWriter(path, batch.schema)
        writer.write_batch(batch)
        n += batch.num_rows
        print(f"  {kind}: {n:,}", flush=True)
    if writer:
        writer.close()
    print(f"{kind}: {n:,} filas -> {out}", flush=True)
