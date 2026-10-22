#!/usr/bin/env python3
"""Package the patched wrapper as app/src/main/assets/graphics_driver/wrapper-vapor.tzst.

The package is the stock wrapper.tzst with only usr/lib/libvulkan_wrapper.so replaced. The
patched build was linked against, and qualified with, the stock package's adrenotools and
hook libraries. Every other member keeps its stock metadata; the replaced library gets a
fixed mtime, so the output is byte-deterministic for a given input library.

Build the library first (pristine 3abf4870 + the six patches in README order, recorded
Meson/NDK recipe), then strip it with plain `llvm-strip` (no flags). The qualified result is
sha256 095c9a81e67c20155669b76ae9b39a6b79a79034f5e4109bde668cde989f1712.

usage: package-wrapper-vapor.py <stripped libvulkan_wrapper.so> [--allow-unqualified]
"""
import argparse, hashlib, io, pathlib, subprocess, sys, tarfile

QUALIFIED = "095c9a81e67c20155669b76ae9b39a6b79a79034f5e4109bde668cde989f1712"
ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/graphics_driver"
LIB = "usr/lib/libvulkan_wrapper.so"
FIXED_MTIME = 1792627200  # 2026-10-22T00:00:00Z

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("library")
    ap.add_argument("--allow-unqualified", action="store_true", help="package a library other than the qualified build")
    ap.add_argument("--out", default=str(ASSETS / "wrapper-vapor.tzst"))
    args = ap.parse_args()
    lib = pathlib.Path(args.library).read_bytes()
    digest = hashlib.sha256(lib).hexdigest()
    if digest != QUALIFIED and not args.allow_unqualified:
        sys.exit(f"error: library sha256 {digest} is not the qualified build {QUALIFIED}")
    stock = subprocess.run(["zstd", "-dc", str(ASSETS / "wrapper.tzst")], capture_output=True, check=True).stdout
    src = tarfile.open(fileobj=io.BytesIO(stock))
    out = io.BytesIO()
    replaced = False
    with tarfile.open(fileobj=out, mode="w", format=tarfile.GNU_FORMAT) as dst:
        for m in src.getmembers():
            n = tarfile.TarInfo(m.name)
            n.type, n.mode, n.uid, n.gid, n.uname, n.gname, n.mtime = m.type, m.mode, m.uid, m.gid, m.uname, m.gname, m.mtime
            if m.name == LIB:
                n.size, n.mtime, replaced = len(lib), FIXED_MTIME, True
                dst.addfile(n, io.BytesIO(lib))
            elif m.isfile():
                data = src.extractfile(m).read()
                n.size = len(data)
                dst.addfile(n, io.BytesIO(data))
            else:
                dst.addfile(n)
    if not replaced:
        sys.exit(f"error: {LIB} not found in the stock package")
    subprocess.run(["zstd", "-19", "-q", "-f", "--no-progress", "-o", args.out], input=out.getvalue(), check=True)
    print(f"{args.out}  sha256 {hashlib.sha256(pathlib.Path(args.out).read_bytes()).hexdigest()}  library {digest}")

if __name__ == "__main__":
    main()
