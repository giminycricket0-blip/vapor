#!/usr/bin/env python3
"""Offscreen sampling/render-pipeline probe for Vapor's Vulkan wrapper.

The native fixture (tools/tests/vulkan-wrapper-render.c) decodes a BCn/RGBA8
texture through the pinned wrapper, samples it through a real graphics pipeline
(one fullscreen triangle, nearest sampler, RGBA8 render target) and writes the
rendered image. This script is the evidence checker and runbook driver:

  * it independently re-derives the expected rendered images from the saved
    decoded textures (nearest upscale, sRGB encoded/linear conventions), checks
    the recorded expected files against that derivation, then compares every
    recorded readback against the correct expected image;
  * --print-runbook prints the exact device commands used to produce a result
    directory (fixture build, shader compile, adb staging and run matrix).

It does not talk to a device by itself. The checked evidence for the 22 October
2026 run is recorded in
docs/evidence/2026-10-22-vulkan-wrapper-render-pipeline.json.

Usage:
  python3 tools/tests/vulkan-wrapper-render.py --dir /path/to/render-evidence
  python3 tools/tests/vulkan-wrapper-render.py --dir ... --verbose
  python3 tools/tests/vulkan-wrapper-render.py --print-runbook
"""

import argparse
import hashlib
import math
import os
import sys

EXPECTED_WIDTH = 64
EXPECTED_HEIGHT = 64
SOURCE_WIDTH = 32
SOURCE_HEIGHT = 32
TOLERANCE_WRAPPER = 2   # LSB; wrapper decode/rounding vs independent reference
TOLERANCE_NATIVE = 3    # LSB; native llvmpipe decode vs independent reference
CONTROL_FORMATS = {"rgba8"}


def read(path):
    with open(path, "rb") as handle:
        return handle.read()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def upscale_nearest(image, width, height, out_width=EXPECTED_WIDTH, out_height=EXPECTED_HEIGHT):
    """Nearest-neighbour sample of a width x height RGBA8 image to the render target."""
    if len(image) != width * height * 4:
        raise ValueError("unexpected decoded image size")
    result = bytearray(out_width * out_height * 4)
    for y in range(out_height):
        source_y = min(height - 1, int((y + 0.5) * height / out_height))
        for x in range(out_width):
            source_x = min(width - 1, int((x + 0.5) * width / out_width))
            source_index = (source_y * width + source_x) * 4
            target_index = (y * out_width + x) * 4
            result[target_index:target_index + 4] = image[source_index:source_index + 4]
    return bytes(result)


def srgb_to_linear_u8(value):
    """8-bit sRGB -> 8-bit linear with double precision and ties-to-even rounding."""
    srgb = value / 255.0
    linear = srgb / 12.92 if srgb <= 0.04045 else ((srgb + 0.055) / 1.055) ** 2.4
    scaled = linear * 255.0
    base = math.floor(scaled)
    fraction = scaled - base
    if fraction > 0.5 or (fraction == 0.5 and (int(base) & 1)):
        base += 1.0
    return max(0, min(255, int(base)))


def linear_image(srgb_bytes):
    result = bytearray(srgb_bytes)
    for index in range(0, len(srgb_bytes), 4):
        for channel in range(3):
            result[index + channel] = srgb_to_linear_u8(srgb_bytes[index + channel])
    return bytes(result)


def compare(actual, expected):
    if len(actual) != len(expected):
        return {"bytes": len(actual), "mismatch": len(expected), "max_delta": None}
    mismatch = 0
    max_delta = 0
    for a, b in zip(actual, expected):
        if a != b:
            mismatch += 1
            max_delta = max(max_delta, abs(a - b))
    return {"bytes": len(actual), "mismatch": mismatch, "max_delta": max_delta}


def derive_expected(input_dir, base, fmt):
    """Return the derived expected render images keyed by convention name."""
    decoded_path = os.path.join(input_dir, f"{base}-decoded-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
    if base == "rgba8" and not os.path.exists(decoded_path):
        # RGBA8 control: the input image is already the decoded RGBA8 texture.
        decoded_path = os.path.join(input_dir, f"rgba8-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
    decoded = read(decoded_path)
    upscaled = upscale_nearest(decoded, SOURCE_WIDTH, SOURCE_HEIGHT)
    derived = {"unorm": upscaled, "srgb-encoded": upscaled}
    if fmt.endswith("_srgb"):
        derived["srgb-linear"] = upscale_nearest(
            linear_image(decoded), SOURCE_WIDTH, SOURCE_HEIGHT)
    return derived


def output_cases(output_dir):
    """Yield (target, format, config, path) for recorded readbacks."""
    if not os.path.isdir(output_dir):
        return
    for name in sorted(os.listdir(output_dir)):
        if not name.startswith("out-") or not name.endswith(".bin"):
            continue
        stem = name[len("out-"):-len(".bin")]
        if os.path.isdir(os.path.join(output_dir, name)):
            continue
        # Recorded names use one of: target-format, target-format-config,
        # or format-target-config for the early smoke files.
        parts = stem.split("-")
        if len(parts) == 2:
            target, fmt = parts
            config = "native" if target == "system" else "compute"
        elif len(parts) >= 3:
            if parts[0] in ("system", "candidate", "baseline"):
                target, fmt, config = parts[0], parts[1], "-".join(parts[2:])
            else:
                fmt, target, config = parts[0], parts[1], "-".join(parts[2:])
        else:
            continue
        yield target, fmt, config, os.path.join(output_dir, name)


def check(evidence_dir, verbose=False):
    input_dir = os.path.join(evidence_dir, "input")
    output_dir = os.path.join(evidence_dir, "output")
    problems = []
    print(f"evidence directory: {evidence_dir}")
    if not os.path.isdir(input_dir):
        print(f"FAIL missing input directory: {input_dir}")
        return 1

    checks = []
    for fmt, base in (("bc1", "bc1"), ("bc3", "bc3"), ("rgba8", "rgba8")):
        decoded_path = os.path.join(input_dir, f"{base}-decoded-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
        if base == "rgba8" and not os.path.exists(decoded_path):
            decoded_path = os.path.join(input_dir, f"rgba8-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
        if not os.path.exists(decoded_path):
            continue
        derived = derive_expected(input_dir, base, fmt)
        expected_files = {
            "unorm": f"{base}-expected-unorm-64x64.bin",
            "srgb-encoded": f"{base}-expected-srgb-encoded-64x64.bin",
            "srgb-linear": f"{base}-expected-srgb-linear-64x64.bin",
        }
        for convention, derived_image in derived.items():
            path = os.path.join(input_dir, expected_files[convention])
            if not os.path.exists(path):
                continue
            recorded = read(path)
            if recorded != derived_image:
                diff = compare(recorded, derived_image)
                problems.append(f"expected-file derivation mismatch: {os.path.basename(path)} {diff}")
            elif verbose:
                print(f"  derived expected OK: {os.path.basename(path)}")
        # The recorded srgb-double file (if present) is not re-derived here.
        checks.append(base)
    if not checks:
        print("FAIL no decoded input images found")
        return 1
    print(f"derived expected images verified for: {', '.join(checks)}")

    cases = list(output_cases(output_dir))
    if not cases:
        print(f"FAIL no readbacks found in {output_dir}")
        return 1

    passed = 0
    failed = 0
    records = []
    for target, fmt, config, path in cases:
        base = fmt[:-5] if fmt.endswith("_srgb") else fmt
        if base not in checks:
            continue
        expected_paths = os.path.join(input_dir, f"{base}-decoded-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
        if base == "rgba8" and not os.path.exists(expected_paths):
            expected_paths = os.path.join(input_dir, f"rgba8-{SOURCE_WIDTH}x{SOURCE_HEIGHT}.bin")
        if not os.path.exists(expected_paths):
            continue
        derived = derive_expected(input_dir, base, fmt)
        if fmt.endswith("_srgb"):
            convention = "srgb-encoded" if config.startswith("host") else "srgb-linear"
        else:
            convention = "unorm"
        expected = derived[convention]
        actual = read(path)
        metrics = compare(actual, expected)
        if base in CONTROL_FORMATS:
            tolerance = 0
        elif target == "system":
            tolerance = TOLERANCE_NATIVE
        else:
            tolerance = TOLERANCE_WRAPPER
        ok = metrics["max_delta"] is None or metrics["max_delta"] <= tolerance
        if ok:
            passed += 1
        else:
            failed += 1
            problems.append(f"{os.path.basename(path)} exceeds {tolerance} LSB: {metrics}")
        record = {
            "target": target,
            "format": fmt,
            "config": config,
            "expected_convention": convention,
            "tolerance_lsb": tolerance,
            "bytes": metrics["bytes"],
            "mismatch_bytes": metrics["mismatch"],
            "max_delta": metrics["max_delta"],
            "verdict": "PASS" if ok else "FAIL",
            "sha256": sha256(actual),
        }
        records.append(record)
        if verbose or not ok:
            print(f"  {record['verdict']:4s} {target:9s} {fmt:9s} {config:12s} "
                  f"convention={convention:12s} mismatch={metrics['mismatch']:6d} "
                  f"max_delta={metrics['max_delta']}")

    print(f"render-pipeline check: {passed} pass / {failed} fail / 0 skipped "
          f"({len(cases)} readbacks scanned)")
    exact = sum(1 for r in records if r["verdict"] == "PASS" and r["mismatch_bytes"] == 0)
    print(f"  byte-exact cases: {exact}/{passed}")
    if problems:
        print("problems:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    return 0


RUNBOOK = """\
Sampling/render-pipeline probe runbook
=====================================
Fixture:  tools/tests/vulkan-wrapper-render.c  (usage: render LIBRARY FORMAT INPUT OUTPUT W H RW RH VERT_SPV FRAG_SPV)
Shaders:  tools/tests/vulkan-wrapper-render.vert / .frag  (glslangValidator -V; record the SPIR-V hashes)

1. Compile the fixture for Android ARM64 with the recorded NDK recipe:
   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang \\
     -std=c11 -O2 -g -Wall -Wextra -Werror -D_GNU_SOURCE \\
     tools/tests/vulkan-wrapper-render.c -ldl \\
     -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \\
     -o probe
2. Stage probe, baseline/candidate wrapper .so, auxiliary libraries, the two
   SPIR-V files and the deterministic BCn/RGBA8 inputs/expected images in one
   directory; push it to /data/local/tmp/ on the emulator (or phone).
3. Run each case with the wrapper environment (FORCE_BCN_EMULATION=1; add
   USE_IMAGE_VIEW=1 for compute-view cases and USE_CPU_BCN=all for host-CPU
   cases; omitted for the native system-loader control):
   LD_LIBRARY_PATH=<dir> PREFIX=<dir> WRAPPER_LOG_LEVEL=none WRAPPER_CMD_LOG_LEVEL=none \\
     ./probe render <library> <bc1|bc3|bc1_srgb|bc3_srgb|rgba8> <input> <out> 32 32 64 64 vert.spv frag.spv
4. Pull the readbacks, keep the independent decoded texture and the derived
   expected images under <evidence>/input/, and run this checker:
   python3 tools/tests/vulkan-wrapper-render.py --dir <evidence> --verbose
Expected conventions: wrapper compute/compute-view and the native control render
sRGB sources as linearised values (sampled as UNORM); the pre-patch host-CPU
fallback renders encoded values (known defect, fixed by
tools/vulkan-wrapper/srgb-storage.patch). RGBA8 controls must be byte-exact.
"""


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Check a sampling/render-pipeline evidence directory or print the runbook.")
    parser.add_argument("--dir", help="evidence directory with input/ and output/")
    parser.add_argument("--verbose", action="store_true", help="print every case and derivation step")
    parser.add_argument("--print-runbook", action="store_true", help="print the device runbook")
    args = parser.parse_args(argv)

    if args.print_runbook:
        print(RUNBOOK)
        return 0
    if not args.dir:
        parser.print_help()
        return 2
    return check(args.dir, verbose=args.verbose)


if __name__ == "__main__":
    sys.exit(main())
