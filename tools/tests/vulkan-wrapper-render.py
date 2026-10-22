#!/usr/bin/env python3
"""Offscreen sampling/render-pipeline probe for Vapor's Vulkan wrapper.

The native fixture (tools/tests/vulkan-wrapper-render.c) decodes a BCn/RGBA8
texture through the pinned wrapper, samples it through a real graphics pipeline
(one fullscreen triangle, nearest sampler, RGBA8 render target) and writes the
rendered image. This script is the checker, the runbook and the one-command
device orchestrator:

  * --dir <evidence>   check a recorded evidence directory (independent
                       re-derivation of the expected render images, alias
                       collapsing, per-case pass/fail);
  * --run              run the whole device pipeline in one command: generate
                       deterministic inputs, decode them with the pinned host
                       bcdec reference, compile the shaders, build and stage the
                       fixture + pinned wrapper builds, boot the AVD, run the
                       31-case matrix, pull the readbacks, check them and write
                       the evidence JSON;
  * --self-test        device-free checks of the expected-image derivation and
                       of the readback naming parser/checker;
  * --print-runbook    print the equivalent manual device commands.

The checked device evidence for the 22 October 2026 run is recorded in
docs/evidence/2026-10-22-vulkan-wrapper-render-orchestrated.json (one-command
run) and docs/evidence/2026-10-22-vulkan-wrapper-render-pipeline.json (salvaged
run checked offline).

Usage:
  python3 tools/tests/vulkan-wrapper-render.py --dir /path/to/render-evidence
  python3 tools/tests/vulkan-wrapper-render.py --run
  python3 tools/tests/vulkan-wrapper-render.py --run --avd VaporProbe36C --port 5560 --verbose
  python3 tools/tests/vulkan-wrapper-render.py --self-test
  python3 tools/tests/vulkan-wrapper-render.py --print-runbook
"""

import argparse
import hashlib
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

EXPECTED_WIDTH = 64
EXPECTED_HEIGHT = 64
SOURCE_WIDTH = 32
SOURCE_HEIGHT = 32
TOLERANCE_WRAPPER = 2   # LSB; wrapper decode/rounding vs independent reference
TOLERANCE_NATIVE = 3    # LSB; native llvmpipe decode vs independent reference
CONTROL_FORMATS = {"rgba8"}
TARGETS = ("system", "candidate", "baseline")
FORMATS = ("bc1", "bc3", "bc1_srgb", "bc3_srgb", "rgba8")


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
        # Recorded names use target-format[-config]; the early smoke run also
        # wrote alias copies named format-target[-config].
        parts = stem.split("-")
        if len(parts) < 2:
            yield None, None, None, os.path.join(output_dir, name)
            continue
        if parts[0] in TARGETS:
            target, fmt, rest = parts[0], parts[1], parts[2:]
        elif parts[1] in TARGETS:
            fmt, target, rest = parts[0], parts[1], parts[2:]
        else:
            yield None, None, None, os.path.join(output_dir, name)
            continue
        if rest:
            config = "-".join(rest)
        else:
            config = "native" if target == "system" else "compute"
        yield target, fmt, config, os.path.join(output_dir, name)


def _check(evidence_dir, verbose=False):
    input_dir = os.path.join(evidence_dir, "input")
    output_dir = os.path.join(evidence_dir, "output")
    problems = []
    print(f"evidence directory: {evidence_dir}")
    if not os.path.isdir(input_dir):
        print(f"FAIL missing input directory: {input_dir}")
        return 1, [], {"error": "missing-input-directory"}

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
        return 1, [], {"error": "no-decoded-inputs"}
    print(f"derived expected images verified for: {', '.join(checks)}")

    scanned = list(output_cases(output_dir))
    if not scanned:
        print(f"FAIL no readbacks found in {output_dir}")
        return 1, [], {"error": "no-readbacks"}

    # Group readbacks by case. Byte-identical alias copies count once; a case
    # whose files disagree is a problem, never silently collapsed.
    grouped = {}
    skipped = []
    for target, fmt, config, path in scanned:
        base = (fmt[:-5] if fmt and fmt.endswith("_srgb") else fmt)
        if target is None or fmt not in FORMATS or base not in checks:
            skipped.append(os.path.basename(path))
            continue
        grouped.setdefault((target, fmt, config), []).append(path)
    aliases = 0
    cases = []
    for key, paths in sorted(grouped.items()):
        digests = {sha256(read(p)) for p in paths}
        if len(digests) > 1:
            problems.append(f"conflicting readbacks for {key}: {[os.path.basename(p) for p in paths]}")
        aliases += len(paths) - 1
        canonical = [p for p in sorted(paths) if os.path.basename(p).startswith(f"out-{key[0]}-")]
        cases.append((*key, (canonical or sorted(paths))[0]))

    passed = 0
    failed = 0
    records = []
    for target, fmt, config, path in cases:
        base = fmt[:-5] if fmt.endswith("_srgb") else fmt
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

    print(f"render-pipeline check: {passed} pass / {failed} fail / {len(skipped)} skipped "
          f"across {len(cases)} unique cases ({len(scanned)} files scanned, "
          f"{aliases} byte-identical alias copies collapsed)")
    exact = sum(1 for r in records if r["verdict"] == "PASS" and r["mismatch_bytes"] == 0)
    print(f"  byte-exact cases: {exact}/{passed}")
    if skipped:
        print(f"  skipped (unrecognised name or no decoded input): {', '.join(skipped)}")
        problems.append(f"{len(skipped)} readback(s) could not be checked")
    summary = {
        "unique_cases": len(cases),
        "files_scanned": len(scanned),
        "aliases": aliases,
        "skipped": len(skipped),
        "passed": passed,
        "failed": failed,
        "byte_exact": exact,
        "problems": [str(problem) for problem in problems],
    }
    if problems:
        print("problems:")
        for problem in problems:
            print(f"  - {problem}")
        return 1, records, summary
    return 0, records, summary


def check(evidence_dir, verbose=False, collect=False):
    """Check an evidence directory. With collect=True return (code, records, summary)."""
    code, records, summary = _check(evidence_dir, verbose)
    if collect:
        return code, records, summary
    return code


# ---------------------------------------------------------------------------
# One-command device orchestration (--run)
#
# Everything below is device-driving infrastructure: deterministic input
# generation, the independent host bcdec reference, shader compilation, the
# fixture build, AVD boot/staging/run/pull, and the evidence JSON. The pass/fail
# decisions themselves stay in _check()/check() above, so --dir and --run use the
# same checker.
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_WRAPPER_BUILD = ("/Users/cheng/Documents/Codex/2026-10-13/82379689/work/"
                         "turnip-refactor-qualification/wrapper-build")
DEFAULT_ADB = "/Users/cheng/Library/Android/sdk/platform-tools/adb"
DEFAULT_EMULATOR = "/Users/cheng/Library/Android/sdk/emulator/emulator"
DEFAULT_NDK = "/Users/cheng/Library/Android/sdk/ndk/27.3.13750724"
DEFAULT_VULKAN_INCLUDE = os.path.join(DEFAULT_WRAPPER_BUILD, "source", "include")
DEFAULT_BCDEC = os.path.join(DEFAULT_WRAPPER_BUILD, "source", "src", "vulkan", "wrapper", "bcdec.h")
DEFAULT_GLSLANG = ("/Users/cheng/Documents/Codex/2026-10-13/82379689/work/"
                   "dxvk-qualification/glslang/bin/glslangValidator")
DEFAULT_REFERENCE_TOOL = os.path.join(REPO_ROOT, "tools", "tests", "vulkan-wrapper-reference.py")
DEFAULT_WORK = "/Users/cheng/wrapper-render-run"
DEFAULT_AVD = "VaporProbe36C"
DEFAULT_PORT = 5560
DEFAULT_EVIDENCE = os.path.join(
    REPO_ROOT, "docs", "evidence", "2026-10-22-vulkan-wrapper-render-orchestrated.json")
DEFAULT_SALVAGED = os.path.join(
    REPO_ROOT, "docs", "evidence", "2026-10-22-vulkan-wrapper-render-pipeline.json")
DATE = "2026-10-22"
REMOTE_DIR_BASE = "vapor-wrapper-render-%s" % DATE.replace("-", "")
CONFIGS = {
    "compute": {"USE_IMAGE_VIEW": "0"},
    "compute-view": {"USE_IMAGE_VIEW": "1"},
    "host-cpu": {"USE_IMAGE_VIEW": "0", "USE_CPU_BCN": "all"},
}
WRAPPER_ENV = {"WRAPPER_LOG_LEVEL": "none", "WRAPPER_CMD_LOG_LEVEL": "none"}

BCDEC_HELPER_SOURCE = r'''/* Host-side independent BC1/BC3 decoder for the render-pipeline probe.
 * Decodes a block-compressed texture to tightly packed RGBA8 using the pinned
 * bcdec.h (MIT OR Unlicense). Not linked into the Android fixture. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BCDEC_STATIC
#include "bcdec.h"

int main(int argc, char **argv) {
   if (argc != 6) {
      fprintf(stderr, "Usage: %s bc1|bc3 INPUT OUTPUT WIDTH HEIGHT\n", argv[0]);
      return 2;
   }
   const char *fmt = argv[1];
   int width = atoi(argv[4]), height = atoi(argv[5]);
   size_t block_bytes = !strcmp(fmt, "bc1") ? 8 : (!strcmp(fmt, "bc3") ? 16 : 0);
   if (!block_bytes || width <= 0 || height <= 0 || width % 4 || height % 4) {
      fprintf(stderr, "unsupported format or extent\n");
      return 2;
   }
   int blocks_x = width / 4, blocks_y = height / 4;
   size_t input_size = (size_t)blocks_x * blocks_y * block_bytes;
   unsigned char *input = malloc(input_size ? input_size : 1);
   unsigned char *decoded = calloc((size_t)width * height * 4, 1);
   if (!input || !decoded) { fprintf(stderr, "allocation failed\n"); return 1; }
   FILE *in = fopen(argv[2], "rb");
   if (!in || fread(input, 1, input_size, in) != input_size) {
      fprintf(stderr, "cannot read input\n");
      return 1;
   }
   fclose(in);
   unsigned char block[64];
   for (int by = 0; by < blocks_y; by++)
      for (int bx = 0; bx < blocks_x; bx++) {
         const unsigned char *source = input + ((size_t)by * blocks_x + bx) * block_bytes;
         memset(block, 0, sizeof(block));
         if (block_bytes == 8) bcdec_bc1(source, block, 16);
         else bcdec_bc3(source, block, 16);
         for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
               memcpy(decoded + (((size_t)(by * 4 + y) * width) + bx * 4 + x) * 4,
                      block + y * 16 + x * 4, 4);
      }
   FILE *out = fopen(argv[3], "wb");
   if (!out || fwrite(decoded, 1, (size_t)width * height * 4, out) != (size_t)width * height * 4) {
      fprintf(stderr, "cannot write output\n");
      return 1;
   }
   fclose(out);
   free(input);
   free(decoded);
   return 0;
}
'''


def sha256_file(path):
    return sha256(read(path))


def file_info(path):
    return {"path": path, "bytes": os.path.getsize(path), "sha256": sha256_file(path)}


def run_process(argv, timeout=900, cwd=None):
    """Run a host command, capturing argv/stdout/stderr/exit code."""
    started = time.time()
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        return {"argv": argv, "command": " ".join(argv), "exit_code": proc.returncode,
                "stdout": proc.stdout, "stderr": proc.stderr,
                "duration_seconds": round(time.time() - started, 2)}
    except subprocess.TimeoutExpired:
        return {"argv": argv, "command": " ".join(argv), "exit_code": None,
                "stdout": "", "stderr": "timeout after %ss" % timeout,
                "duration_seconds": round(time.time() - started, 2)}
    except FileNotFoundError as error:
        return {"argv": argv, "command": " ".join(argv), "exit_code": None,
                "stdout": "", "stderr": str(error),
                "duration_seconds": round(time.time() - started, 2)}


def write_json(path, data):
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")


def import_reference_tool(path):
    """Import the committed independent Python BC reference implementation."""
    spec = importlib.util.spec_from_file_location("vapor_wrapper_reference", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# -- deterministic inputs and independent host decode -----------------------

def generate_inputs(evidence_dir, reference_path):
    """Write deterministic 32x32 BC1/BC3/RGBA8 inputs; return file info + module."""
    input_dir = os.path.join(evidence_dir, "input")
    os.makedirs(input_dir, exist_ok=True)
    reference = import_reference_tool(reference_path)
    bc1, _ = reference.generate_bc1_blocks(SOURCE_WIDTH, SOURCE_HEIGHT, 0)
    bc3, _ = reference.generate_bc3_blocks(SOURCE_WIDTH, SOURCE_HEIGHT, 0)
    rgba8 = bytes((x * 8 + y * 3 + channel * 17) & 0xFF
                  for y in range(SOURCE_HEIGHT) for x in range(SOURCE_WIDTH)
                  for channel in range(4))
    files = {}
    for name, data in (("bc1", bc1), ("bc3", bc3), ("rgba8", rgba8)):
        path = os.path.join(input_dir, "%s-%dx%d.bin" % (name, SOURCE_WIDTH, SOURCE_HEIGHT))
        with open(path, "wb") as handle:
            handle.write(data)
        files[name] = file_info(path)
    return files, reference


def compile_host_decoder(evidence_dir, bcdec_path):
    """Compile the host bcdec decoder; return tool info and the C source path."""
    host_dir = os.path.join(evidence_dir, "host")
    os.makedirs(host_dir, exist_ok=True)
    source_path = os.path.join(host_dir, "bcdec_decode.c")
    with open(source_path, "w") as handle:
        handle.write(BCDEC_HELPER_SOURCE)
    binary = os.path.join(host_dir, "bcdec_decode")
    if not os.path.exists(bcdec_path):
        raise RuntimeError("pinned bcdec.h not found: %s" % bcdec_path)
    compile_result = run_process([
        "cc", "-O2", "-std=c11", "-I" + os.path.dirname(bcdec_path), source_path, "-o", binary])
    if compile_result["exit_code"] != 0 or not os.path.exists(binary):
        raise RuntimeError("host bcdec decoder build failed:\n%s" % compile_result["stderr"])
    version = run_process(["cc", "--version"])
    bcdec_text = read(bcdec_path).decode("utf-8", "replace")
    return {
        "decoder": "bcdec.h",
        "decoder_path": bcdec_path,
        "decoder_sha256": sha256_file(bcdec_path),
        "decoder_license": ("MIT OR Unlicense (bcdec.h alternative A = MIT, "
                            "alternative B = The Unlicense)"),
        "decoder_version": ("0.%s" % re.search(r"#define BCDEC_VERSION_MINOR (\d+)", bcdec_text).group(1)
                            if re.search(r"#define BCDEC_VERSION_MINOR (\d+)", bcdec_text) else "unknown"),
        "helper_source": source_path,
        "helper_source_sha256": sha256_file(source_path),
        "helper_binary": binary,
        "helper_binary_sha256": sha256_file(binary),
        "compile_command": compile_result["command"],
        "compiler": version["stdout"].splitlines()[0] if version["stdout"] else "cc",
    }


def decode_with_host_helper(evidence_dir, host_info):
    """Decode the BC inputs with the host bcdec helper; return decoded file info."""
    input_dir = os.path.join(evidence_dir, "input")
    decoded = {}
    for base in ("bc1", "bc3"):
        source = os.path.join(input_dir, "%s-%dx%d.bin" % (base, SOURCE_WIDTH, SOURCE_HEIGHT))
        target = os.path.join(input_dir, "%s-decoded-%dx%d.bin"
                              % (base, SOURCE_WIDTH, SOURCE_HEIGHT))
        result = run_process([host_info["helper_binary"], base, source, target,
                              str(SOURCE_WIDTH), str(SOURCE_HEIGHT)])
        if result["exit_code"] != 0 or not os.path.exists(target):
            raise RuntimeError("host bcdec decode failed for %s:\n%s" % (base, result["stderr"]))
        decoded[base] = file_info(target)
    return decoded


def decoder_cross_check(evidence_dir, reference):
    """Compare the pinned bcdec host decode with the independent Python reference."""
    input_dir = os.path.join(evidence_dir, "input")
    result = {}
    for base, python_decode in (("bc1", reference.decode_bc1_rgba8),
                                ("bc3", reference.decode_bc3_rgba8)):
        compressed = read(os.path.join(input_dir, "%s-%dx%d.bin"
                                       % (base, SOURCE_WIDTH, SOURCE_HEIGHT)))
        python_rgba = python_decode(compressed, SOURCE_WIDTH, SOURCE_HEIGHT)
        bcdec_rgba = read(os.path.join(input_dir, "%s-decoded-%dx%d.bin"
                                       % (base, SOURCE_WIDTH, SOURCE_HEIGHT)))
        differences = [(i, a, b) for i, (a, b) in enumerate(zip(bcdec_rgba, python_rgba))
                       if a != b]
        result[base] = {
            "bytes": len(bcdec_rgba),
            "differing_bytes": len(differences),
            "max_channel_delta": max((abs(a - b) for _, a, b in differences), default=0),
            "bcdec_sha256": sha256(bcdec_rgba),
            "python_reference_sha256": sha256(python_rgba),
        }
    return result


def write_expected_images(evidence_dir):
    """Derive and write the expected render images from the decoded textures."""
    input_dir = os.path.join(evidence_dir, "input")
    written = {}
    for base in ("bc1", "bc3"):
        decoded = read(os.path.join(input_dir, "%s-decoded-%dx%d.bin"
                                    % (base, SOURCE_WIDTH, SOURCE_HEIGHT)))
        images = {
            "unorm": upscale_nearest(decoded, SOURCE_WIDTH, SOURCE_HEIGHT),
            "srgb-encoded": upscale_nearest(decoded, SOURCE_WIDTH, SOURCE_HEIGHT),
            "srgb-linear": upscale_nearest(linear_image(decoded), SOURCE_WIDTH, SOURCE_HEIGHT),
        }
        for convention, image in images.items():
            name = "%s-expected-%s-%dx%d.bin" % (
                base, convention, EXPECTED_WIDTH, EXPECTED_HEIGHT)
            path = os.path.join(input_dir, name)
            with open(path, "wb") as handle:
                handle.write(image)
            written[name] = file_info(path)
    rgba8 = read(os.path.join(input_dir, "rgba8-%dx%d.bin" % (SOURCE_WIDTH, SOURCE_HEIGHT)))
    for convention in ("unorm", "srgb-encoded"):
        name = "rgba8-expected-%s-%dx%d.bin" % (convention, EXPECTED_WIDTH, EXPECTED_HEIGHT)
        path = os.path.join(input_dir, name)
        with open(path, "wb") as handle:
            handle.write(upscale_nearest(rgba8, SOURCE_WIDTH, SOURCE_HEIGHT))
        written[name] = file_info(path)
    return written


# -- shaders, fixture build, staging ---------------------------------------

def compile_shaders(evidence_dir, glslang, vert_source, frag_source):
    shaders_dir = os.path.join(evidence_dir, "shaders")
    os.makedirs(shaders_dir, exist_ok=True)
    if not os.path.exists(glslang):
        raise RuntimeError("glslangValidator not found: %s" % glslang)
    version = run_process([glslang, "--version"])
    shaders = {}
    for name, source_path in (("fullscreen.vert", vert_source), ("sample.frag", frag_source)):
        spirv = os.path.join(shaders_dir, name + ".spv")
        command = run_process([glslang, "-V", "--target-env", "vulkan1.2", "-o", spirv,
                               source_path])
        if command["exit_code"] != 0 or not os.path.exists(spirv):
            raise RuntimeError("shader compile failed for %s:\n%s"
                               % (name, command["stderr"]))
        shaders[name] = {
            "glsl": file_info(source_path),
            "spirv": file_info(spirv),
            "command": command["command"],
        }
    return {
        "compiler": glslang,
        "compiler_sha256": sha256_file(glslang),
        "compiler_version": (version["stdout"].splitlines()[0]
                             if version["stdout"] else "glslangValidator"),
        "target_environment": "vulkan1.2",
        "shaders": shaders,
    }


def build_fixture(evidence_dir, ndk, include_dir, source_path):
    bin_dir = os.path.join(evidence_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)
    clang = os.path.join(ndk, "toolchains", "llvm", "prebuilt", "darwin-x86_64", "bin",
                         "aarch64-linux-android34-clang")
    if not os.path.exists(clang):
        raise RuntimeError("NDK clang not found: %s" % clang)
    if not os.path.isdir(include_dir):
        raise RuntimeError("Vulkan header include directory not found: %s" % include_dir)
    binary = os.path.join(bin_dir, "vulkan-wrapper-render")
    argv = [clang, "-std=c11", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-D_GNU_SOURCE",
            "-I" + include_dir, source_path, "-ldl",
            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
            "-o", binary]
    result = run_process(argv)
    if result["exit_code"] != 0 or not os.path.exists(binary):
        raise RuntimeError("fixture build failed:\n%s" % result["stderr"])
    return {
        "source": file_info(source_path),
        "binary": file_info(binary),
        "build_command": argv,
        "toolchain": "NDK %s aarch64-linux-android34-clang, 16 KB max/common page-size flags"
                     % os.path.basename(ndk.rstrip("/")),
        "vulkan_headers": include_dir,
    }


def prepare_stage(evidence_dir, fixture, wrapper_build, shaders, inputs):
    """Assemble the flat staging directory pushed to the device."""
    stage_dir = os.path.join(evidence_dir, "stage")
    if os.path.isdir(stage_dir):
        shutil.rmtree(stage_dir)
    os.makedirs(stage_dir)
    staged = {}

    def add(source, name):
        if not os.path.exists(source):
            raise RuntimeError("staging source missing: %s" % source)
        target = os.path.join(stage_dir, name)
        shutil.copyfile(source, target)
        os.chmod(target, 0o755)
        staged[name] = file_info(target)

    add(fixture["binary"]["path"], "vulkan-wrapper-render")
    native_probe = os.path.join(wrapper_build, "native-probe")
    add(os.path.join(native_probe, "baseline.so"), "baseline.so")
    add(os.path.join(native_probe, "candidate.so"), "candidate.so")
    for name in sorted(os.listdir(native_probe)):
        if name.startswith("lib") and ".so" in name:
            add(os.path.join(native_probe, name), name)
    add(shaders["shaders"]["fullscreen.vert"]["spirv"]["path"], "fullscreen.vert.spv")
    add(shaders["shaders"]["sample.frag"]["spirv"]["path"], "sample.frag.spv")
    for base in ("bc1", "bc3", "rgba8"):
        add(inputs[base]["path"], "%s-%dx%d.bin" % (base, SOURCE_WIDTH, SOURCE_HEIGHT))
    return stage_dir, staged


# -- emulator / adb ---------------------------------------------------------

def adb_devices(adb):
    result = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=60)
    devices = []
    for line in result.stdout.replace("\r\n", "\n").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def adb_state(adb, serial):
    result = subprocess.run([adb, "-s", serial, "get-state"], capture_output=True,
                            text=True, timeout=30)
    return result.stdout.strip()


def adb_shell(adb, serial, command, timeout=300):
    started = time.time()
    try:
        proc = subprocess.run([adb, "-s", serial, "shell", command], capture_output=True,
                              text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"command": command, "exit_code": None, "stdout": "",
                "stderr": "timeout after %ss" % timeout,
                "duration_seconds": round(time.time() - started, 2)}
    return {"command": command, "exit_code": proc.returncode,
            "stdout": proc.stdout.replace("\r\n", "\n"),
            "stderr": proc.stderr.replace("\r\n", "\n"),
            "duration_seconds": round(time.time() - started, 2)}


def adb_push(adb, serial, source, remote_dir):
    result = subprocess.run([adb, "-s", serial, "push", source, remote_dir.rstrip("/") + "/"],
                            capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError("adb push failed for %s:\n%s" % (source, result.stderr))
    return result.stdout.strip()


def adb_pull(adb, serial, remote, local):
    result = subprocess.run([adb, "-s", serial, "pull", remote, local], capture_output=True,
                            text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError("adb pull failed for %s:\n%s" % (remote, result.stderr))
    return result.stdout.strip()


def port_in_use(port):
    result = subprocess.run(["lsof", "-nP", "-iTCP:%d" % port, "-sTCP:LISTEN"],
                            capture_output=True, text=True)
    return bool(result.stdout.strip())


def qemu_running_for(avd):
    result = subprocess.run(["pgrep", "-fl", "qemu-system"], capture_output=True, text=True)
    return any(("-avd %s" % avd) in line for line in result.stdout.splitlines())


def wait_for_boot(adb, serial, timeout=300):
    """Wait for boot_completed, with an observable fallback for headless AVDs."""
    started = time.time()
    observed = {"sys.boot_completed": "", "fallback": False, "state": ""}
    while time.time() - started < timeout:
        observed["state"] = adb_state(adb, serial)
        if observed["state"] == "device":
            booted = adb_shell(adb, serial, "getprop sys.boot_completed", timeout=30)
            observed["sys.boot_completed"] = booted["stdout"].strip()
            if observed["sys.boot_completed"] == "1":
                return True, observed, round(time.time() - started, 1)
            # Some headless launches do not expose sys.boot_completed promptly;
            # fall back to a usable-shell probe and record that it was used.
            if time.time() - started > 60:
                probe = adb_shell(
                    adb, serial,
                    "echo PAGE=$(getconf PAGE_SIZE); test -f /system/lib64/libvulkan.so "
                    "&& echo LOADER=ok", timeout=30)
                if "PAGE=" in probe["stdout"] and "LOADER=ok" in probe["stdout"]:
                    observed["fallback"] = True
                    return True, observed, round(time.time() - started, 1)
        time.sleep(2)
    return False, observed, round(time.time() - started, 1)


def boot_emulator(emulator, adb, avd, port, memory, cores, log_path):
    serial = "emulator-%d" % port
    if port_in_use(port):
        raise RuntimeError("port %d is already listening; refusing to boot" % port)
    if qemu_running_for(avd):
        raise RuntimeError("an emulator for %s is already running" % avd)
    if serial in adb_devices(adb):
        raise RuntimeError("%s is already attached to adb; use --attach" % serial)
    launch = [emulator, "-avd", avd, "-port", str(port), "-no-window", "-no-audio",
              "-no-snapshot-load", "-no-snapshot-save", "-gpu", "lavapipe",
              "-no-boot-anim", "-memory", str(memory), "-cores", str(cores)]
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    log = open(log_path, "w")
    process = subprocess.Popen(launch, stdout=log, stderr=subprocess.STDOUT)
    ok, observed, seconds = wait_for_boot(adb, serial)
    if not ok:
        kill_emulator(adb, serial)
        raise RuntimeError("emulator %s did not boot within the timeout (%s)" % (avd, observed))
    return {"process": process, "launch_command": launch, "serial": serial,
            "boot_seconds": seconds, "boot_observed": observed}


def kill_emulator(adb, serial):
    subprocess.run([adb, "-s", serial, "emu", "kill"], capture_output=True, text=True,
                   timeout=60)
    for _ in range(60):
        if serial not in adb_devices(adb):
            return True
        time.sleep(1)
    return False


def collect_device_facts(adb, serial):
    command = ("echo PAGE_SIZE=$(getconf PAGE_SIZE); "
               "echo BOOT_COMPLETED=$(getprop sys.boot_completed); "
               "echo SDK=$(getprop ro.build.version.sdk); "
               "echo RELEASE=$(getprop ro.build.version.release); "
               "echo ABI=$(getprop ro.product.cpu.abi); "
               "echo PRODUCT=$(getprop ro.product.name); "
               "echo MODEL=$(getprop ro.product.model); "
               "echo FINGERPRINT=$(getprop ro.build.fingerprint); "
               "echo HARDWARE_VULKAN=$(getprop ro.hardware.vulkan); "
               "echo UNAME=$(uname -a)")
    result = adb_shell(adb, serial, command)
    facts = {}
    for line in result["stdout"].splitlines():
        line = line.strip()
        if "=" in line:
            key, value = line.split("=", 1)
            facts[key] = value
    facts["raw"] = result["stdout"]
    facts["command"] = command
    return facts


# -- run matrix -------------------------------------------------------------

def matrix_cases():
    """Full matrix: system native x5, baseline/candidate x3 configs x4 BC + rgba8."""
    cases = [("system", fmt, "native") for fmt in FORMATS]
    for target in ("baseline", "candidate"):
        for fmt in ("bc1", "bc3", "bc1_srgb", "bc3_srgb"):
            for config in ("compute", "compute-view", "host-cpu"):
                cases.append((target, fmt, config))
        cases.append((target, "rgba8", "compute"))
    return cases


def case_base(fmt):
    return fmt[:-5] if fmt.endswith("_srgb") else fmt


def case_library(target, remote_dir):
    if target == "system":
        return "/system/lib64/libvulkan.so"
    return "%s/%s.so" % (remote_dir, target)


def case_environment(target, config, remote_dir):
    environment = {"LD_LIBRARY_PATH": remote_dir, "PREFIX": remote_dir}
    environment.update(WRAPPER_ENV)
    if target == "system":
        return environment
    environment["FORCE_BCN_EMULATION"] = "1"
    environment.update(CONFIGS[config])
    return environment


def case_output_name(target, fmt, config):
    return "out-%s-%s-%s.bin" % (target, fmt, config)


def run_case(adb, serial, remote_dir, target, fmt, config, timeout=300):
    base = case_base(fmt)
    library = case_library(target, remote_dir)
    output = case_output_name(target, fmt, config)
    environment = case_environment(target, config, remote_dir)
    environment_text = " ".join("%s=%s" % (key, value) for key, value in environment.items())
    command = ("cd %s && %s ./vulkan-wrapper-render render %s %s %s-%dx%d.bin %s %d %d %d %d "
               "fullscreen.vert.spv sample.frag.spv"
               % (remote_dir, environment_text, library, fmt, base, SOURCE_WIDTH, SOURCE_HEIGHT,
                  output, SOURCE_WIDTH, SOURCE_HEIGHT, EXPECTED_WIDTH, EXPECTED_HEIGHT))
    shell = adb_shell(adb, serial, command, timeout=timeout)
    lines = shell["stdout"].splitlines()
    values = {}
    for line in lines:
        if "=" in line and not line.startswith("STEP_"):
            key, value = line.split("=", 1)
            values[key] = value
    return {
        "target": target,
        "format": fmt,
        "config": config,
        "output_name": output,
        "library": library,
        "environment": environment,
        "command": command,
        "exit_code": shell["exit_code"],
        "result_ok": values.get("RESULT") == "ok" and shell["exit_code"] == 0,
        "fail_stage": values.get("FAIL_STAGE"),
        "fail_result": values.get("FAIL_RESULT"),
        "device_name": values.get("DEVICE_NAME"),
        "device_api": values.get("DEVICE_API"),
        "device_driver_name": values.get("DEVICE_DRIVER_NAME"),
        "device_driver_info": values.get("DEVICE_DRIVER_INFO"),
        "page_size": values.get("PAGE_SIZE"),
        "stdout_tail": "\n".join(lines[-6:]),
        "stderr": shell["stderr"],
        "duration_seconds": shell["duration_seconds"],
    }


def pull_outputs(adb, serial, remote_dir, output_dir, cases):
    if os.path.isdir(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir)
    pulled = {}
    for target, fmt, config in cases:
        name = case_output_name(target, fmt, config)
        local = os.path.join(output_dir, name)
        adb_pull(adb, serial, "%s/%s" % (remote_dir, name), local)
        bytes_expected = EXPECTED_WIDTH * EXPECTED_HEIGHT * 4
        if os.path.getsize(local) != bytes_expected:
            raise RuntimeError("readback %s has %d bytes, expected %d"
                               % (name, os.path.getsize(local), bytes_expected))
        pulled[name] = file_info(local)
    return pulled


# -- receipt ----------------------------------------------------------------

def group_name(record):
    if record["format"] == "rgba8":
        return "RGBA8 controls"
    if record["target"] == "system":
        return "System loader (native llvmpipe)"
    if record["config"] == "host-cpu":
        return "Wrapper host-cpu sRGB" if record["format"].endswith("_srgb") \
            else "Wrapper host-cpu UNORM"
    return "Wrapper compute/compute-view sRGB" if record["format"].endswith("_srgb") \
        else "Wrapper compute/compute-view UNORM"


def group_results(records):
    groups = {}
    for record in records:
        name = group_name(record)
        group = groups.setdefault(name, {"cases": 0, "pass": 0, "fail": 0,
                                         "byte_exact": 0, "max_delta": 0,
                                         "expected_conventions": set()})
        group["cases"] += 1
        group["pass" if record["verdict"] == "PASS" else "fail"] += 1
        if record["verdict"] == "PASS" and record["mismatch_bytes"] == 0:
            group["byte_exact"] += 1
        if record["max_delta"] is not None:
            group["max_delta"] = max(group["max_delta"], record["max_delta"])
        group["expected_conventions"].add(record["expected_convention"])
    for group in groups.values():
        group["expected_conventions"] = sorted(group["expected_conventions"])
    return groups


def format_stats(records, group):
    """Per-format pass/byte-exact/max-delta stats for one group."""
    stats = {}
    for record in records:
        if group_name(record) != group:
            continue
        entry = stats.setdefault(record["format"],
                                 {"cases": 0, "pass": 0, "byte_exact": 0, "max_delta": 0})
        entry["cases"] += 1
        if record["verdict"] == "PASS":
            entry["pass"] += 1
        if record["verdict"] == "PASS" and record["mismatch_bytes"] == 0:
            entry["byte_exact"] += 1
        if record["max_delta"] is not None:
            entry["max_delta"] = max(entry["max_delta"], record["max_delta"])
    return stats


def salvage_report(records, summary):
    """Compare the observed groups with the salvaged run's recorded expectations."""
    by_group = group_results(records)
    statements = []

    def group(name):
        return by_group.get(name, {})

    unorm = group("Wrapper compute/compute-view UNORM")
    srgb = group("Wrapper compute/compute-view sRGB")
    host_srgb = group("Wrapper host-cpu sRGB")
    native = group("System loader (native llvmpipe)")
    controls = group("RGBA8 controls")
    unorm_stats = format_stats(records, "Wrapper compute/compute-view UNORM")
    native_stats = format_stats(records, "System loader (native llvmpipe)")

    unorm_exact = (unorm.get("cases", 0) > 0 and unorm.get("fail", 1) == 0
                   and unorm.get("byte_exact", -1) == unorm.get("cases", 0))
    srgb_within_1 = (srgb.get("cases", 0) > 0 and srgb.get("fail", 1) == 0
                     and srgb.get("max_delta", 99) <= 1)
    host_srgb_encoded = (host_srgb.get("cases", 0) > 0 and host_srgb.get("fail", 1) == 0
                         and host_srgb.get("expected_conventions") == ["srgb-encoded"])
    native_within_3 = (native.get("cases", 0) > 0 and native.get("fail", 1) == 0
                       and native.get("max_delta", 99) <= 3)
    controls_exact = (controls.get("cases", 0) > 0 and controls.get("fail", 1) == 0
                      and controls.get("max_delta", 99) == 0)
    deliverables = [
        ("31 unique cases, 0 aliases",
         summary.get("unique_cases") == 31 and summary.get("aliases") == 0
         and summary.get("files_scanned") == 31,
         "%d unique cases, %d files scanned, %d aliases, %d skipped"
         % (summary.get("unique_cases"), summary.get("files_scanned"),
            summary.get("aliases"), summary.get("skipped"))),
        ("UNORM compute/compute-view exact",
         unorm_exact,
         "%d/%d pass, %d byte-exact, max delta %s; BC1 %d/%d byte-exact, BC3 %d/%d byte-exact "
         "with max delta %s (BC3 differs from the salvaged expectation because this run decodes "
         "with the bcdec reference instead of the Python reference decoder)"
         % (unorm.get("pass", 0), unorm.get("cases", 0), unorm.get("byte_exact", 0),
            unorm.get("max_delta"),
            unorm_stats.get("bc1", {}).get("byte_exact", 0),
            unorm_stats.get("bc1", {}).get("cases", 0),
            unorm_stats.get("bc3", {}).get("byte_exact", 0),
            unorm_stats.get("bc3", {}).get("cases", 0),
            unorm_stats.get("bc3", {}).get("max_delta"))),
        ("sRGB compute/compute-view within 1 LSB of linear",
         srgb_within_1,
         "%d/%d pass, max delta %s" % (srgb.get("pass", 0), srgb.get("cases", 0),
                                       srgb.get("max_delta"))),
        ("pre-patch host-cpu sRGB = encoded convention",
         host_srgb_encoded,
         "%d/%d pass against the encoded convention, max delta %s"
         % (host_srgb.get("pass", 0), host_srgb.get("cases", 0),
            host_srgb.get("max_delta"))),
        ("native within 3 LSB",
         native_within_3,
         "%d/%d pass, max delta %s (BC1 %s, BC3 %s)"
         % (native.get("pass", 0), native.get("cases", 0), native.get("max_delta"),
            native_stats.get("bc1", {}).get("max_delta"),
            native_stats.get("bc3", {}).get("max_delta"))),
        ("RGBA8 controls exact",
         controls_exact,
         "%d/%d pass, max delta %s" % (controls.get("pass", 0), controls.get("cases", 0),
                                       controls.get("max_delta"))),
    ]
    for name, ok, detail in deliverables:
        statements.append({"expectation": name, "matched": bool(ok), "observed": detail})
    deviations = [statement for statement in statements if not statement["matched"]]
    return statements, by_group, deviations


def build_receipt(args, context):
    records = context["records"]
    summary = context["summary"]
    statements, groups, deviations = salvage_report(records, summary)
    runs_by_key = {(run["target"], run["format"], run["config"]): run
                   for run in context["runs"]}
    cases = []
    for record in records:
        key = (record["target"], record["format"], record["config"])
        run = runs_by_key.get(key, {})
        case = dict(record)
        case["command"] = run.get("command")
        case["exit_code"] = run.get("exit_code")
        case["readback_sha256"] = record["sha256"]
        case["readback_bytes"] = record["bytes"]
        cases.append(case)
    return {
        "date": DATE,
        "label": context["label"],
        "scope": ("One-command offscreen sampling/render-pipeline run: generate deterministic "
                  "BC1/BC3/RGBA8 inputs, decode them with the pinned host bcdec reference, "
                  "compile the committed shaders, build and stage the Android fixture, boot the "
                  "4 KB AVD VaporProbe36C, run the 31-case matrix through the system loader and "
                  "the pinned wrapper candidate/baseline (compute, compute-view, host-cpu "
                  "decode), read the rendered images back and check them against the expected "
                  "images. Software Vulkan emulator only; no phone/Adreno result claimed."),
        "base_commit": context["base_commit"],
        "tool": {
            "path": "tools/tests/vulkan-wrapper-render.py",
            "sha256": sha256_file(os.path.abspath(__file__)),
            "one_command": context["one_command"],
            "modes": ["--dir (offline checker)", "--run (this receipt)", "--self-test",
                      "--print-runbook"],
        },
        "fixture": context["fixture"],
        "shaders": context["shaders"],
        "host_reference": {
            "bcdec": {
                "path": context["host"]["decoder_path"],
                "sha256": context["host"]["decoder_sha256"],
                "license": context["host"]["decoder_license"],
                "version": context["host"]["decoder_version"],
            },
            "helper": {
                "source": context["host"]["helper_source"],
                "source_sha256": context["host"]["helper_source_sha256"],
                "binary_sha256": context["host"]["helper_binary_sha256"],
                "compile_command": context["host"]["compile_command"],
                "compiler": context["host"]["compiler"],
            },
            "independent_python_reference": {
                "path": DEFAULT_REFERENCE_TOOL,
                "sha256": sha256_file(DEFAULT_REFERENCE_TOOL),
                "use": "deterministic input generation and a second decoder for the cross-check",
            },
            "decoder_cross_check": context["cross_check"],
        },
        "inputs": {
            "generated": context["inputs"],
            "decoded": context["decoded"],
            "expected": context["expected"],
            "salvaged_input_match": context["input_match"],
        },
        "emulator": context["emulator"],
        "matrix": {
            "cases": len(context["runs"]),
            "targets": [{"target": "system", "library": "/system/lib64/libvulkan.so",
                         "configs": ["native"]},
                        {"target": "baseline", "library": "baseline.so",
                         "configs": ["compute", "compute-view", "host-cpu"]},
                        {"target": "candidate", "library": "candidate.so",
                         "configs": ["compute", "compute-view", "host-cpu"]}],
            "formats": list(FORMATS),
            "conventions": "nearest sampler, fullscreen triangle, RGBA8 UNORM offscreen target, "
                           "32x32 texture sampled into a 64x64 target",
        },
        "runs": context["runs"],
        "results": {
            "checker": "same _check() used by --dir; invoked internally by --run",
            "summary": summary,
            "cases": cases,
            "groups": groups,
            "cross_checks": context["cross_checks"],
        },
        "salvaged_comparison": {
            "salvaged_receipt": args.salvaged,
            "expectations": statements,
            "deviations": deviations,
            "reference_decoder_change": context["reference_change"],
            "device_consistency": "The salvaged run used AVD Vapor_UI_API36 on "
                                  "emulator-5556; this one-command run used AVD VaporProbe36C on "
                                  "emulator-5560. Both are Android 16/API 36, 4 KB, llvmpipe; the "
                                  "observed device/driver strings are recorded in the run log.",
        },
        "confirmed": context["confirmed"],
        "open": context["open"],
        "limitations": context["limitations"],
        "raw_artifacts": {
            "evidence_directory": context["evidence_dir"],
            "input": os.path.join(context["evidence_dir"], "input"),
            "output": os.path.join(context["evidence_dir"], "output"),
            "logs": os.path.join(context["evidence_dir"], "logs"),
            "shaders": os.path.join(context["evidence_dir"], "shaders"),
            "stage": os.path.join(context["evidence_dir"], "stage"),
            "host_reference": os.path.join(context["evidence_dir"], "host"),
            "emulator_log": context["emulator_log"],
        },
        "application": {
            "commits": False,
            "upstream_submission": False,
            "app_changes": False,
            "driver_install": False,
            "phone_or_adreno_result": False,
        },
    }


def host_cpu_srgb_cross_check(evidence_dir, records):
    """Show that host-cpu sRGB readbacks match encoded, not linearised, values."""
    checks = {}
    for record in records:
        if record["config"] != "host-cpu" or not record["format"].endswith("_srgb"):
            continue
        base = record["format"][:-5]
        actual = read(os.path.join(evidence_dir, "output", "out-%s-%s-%s.bin" % (
            record["target"], record["format"], record["config"])))
        linear = read(os.path.join(evidence_dir, "input",
                                   "%s-expected-srgb-linear-%dx%d.bin"
                                   % (base, EXPECTED_WIDTH, EXPECTED_HEIGHT)))
        metrics = compare(actual, linear)
        checks["%s-%s" % (record["target"], record["format"])] = {
            "bytes": metrics["bytes"],
            "mismatch_bytes_vs_linear": metrics["mismatch"],
            "max_delta_vs_linear": metrics["max_delta"],
            "note": "host-cpu readback is compared with the linearised expectation here; a "
                    "large delta confirms the known encoded-vs-linearised storage split",
        }
    return checks


# -- one-command run --------------------------------------------------------

def run_mode(args):
    evidence_dir = os.path.abspath(args.work)
    adb = args.adb
    serial = args.serial or ("emulator-%d" % args.port)
    started = time.time()
    emulator = None
    log = lambda message: print(message, flush=True)

    if os.path.isdir(evidence_dir) and os.listdir(evidence_dir) and not args.overwrite:
        print("FAIL evidence directory %s is not empty; pass --overwrite to replace it"
              % evidence_dir)
        return 1
    if args.overwrite and os.path.isdir(evidence_dir):
        shutil.rmtree(evidence_dir)
    os.makedirs(evidence_dir, exist_ok=True)
    for sub in ("input", "output", "logs", "shaders", "bin", "host", "stage"):
        os.makedirs(os.path.join(evidence_dir, sub), exist_ok=True)
    emulator_log = os.path.join(evidence_dir, "logs", "emulator.log")
    boot = None

    try:
        log("== generating deterministic inputs")
        inputs, reference = generate_inputs(evidence_dir, args.reference_tool)
        log("== compiling the host bcdec decoder")
        host = compile_host_decoder(evidence_dir, args.bcdec)
        decoded = decode_with_host_helper(evidence_dir, host)
        cross_check = decoder_cross_check(evidence_dir, reference)
        expected = write_expected_images(evidence_dir)
        log("== compiling shaders with %s" % args.glslang)
        shaders = compile_shaders(evidence_dir, args.glslang,
                                  os.path.join(REPO_ROOT, "tools", "tests",
                                               "vulkan-wrapper-render.vert"),
                                  os.path.join(REPO_ROOT, "tools", "tests",
                                               "vulkan-wrapper-render.frag"))
        log("== building the Android fixture")
        fixture = build_fixture(evidence_dir, args.ndk, args.vulkan_include,
                                os.path.join(REPO_ROOT, "tools", "tests",
                                             "vulkan-wrapper-render.c"))
        stage_dir, staged = prepare_stage(evidence_dir, fixture, args.wrapper_build,
                                          shaders, inputs)
        log("== staging to %s" % args.work)
        remote_dir = "/data/local/tmp/" + REMOTE_DIR_BASE
        if args.attach:
            if adb_state(adb, serial) != "device":
                raise RuntimeError("--attach requested but %s is not attached" % serial)
            log("== attached to %s" % serial)
        else:
            log("== booting AVD %s on port %d" % (args.avd, args.port))
            boot = boot_emulator(args.emulator, adb, args.avd, args.port, args.memory,
                                 args.cores, emulator_log)
        device_facts = collect_device_facts(adb, serial)
        if "PAGE_SIZE" not in device_facts and boot is not None:
            raise RuntimeError("could not read device facts after boot: %s"
                               % device_facts.get("raw"))
        adb_shell(adb, serial, "rm -rf %s" % remote_dir)
        adb_shell(adb, serial, "mkdir -p %s" % remote_dir)
        for name in sorted(staged):
            adb_push(adb, serial, staged[name]["path"], remote_dir)
        chmod = adb_shell(adb, serial, "chmod 755 %s/vulkan-wrapper-render" % remote_dir)
        if chmod["exit_code"] != 0:
            raise RuntimeError("chmod failed: %s" % chmod["stderr"])
        device_probe_sha = adb_shell(adb, serial, "cd %s && sha256sum vulkan-wrapper-render"
                                     % remote_dir)["stdout"].split()[0]
        if device_probe_sha != fixture["binary"]["sha256"]:
            raise RuntimeError("device probe hash %s != host %s"
                               % (device_probe_sha, fixture["binary"]["sha256"]))
        log("== running %d matrix cases" % len(matrix_cases()))
        runs = []
        for index, (target, fmt, config) in enumerate(matrix_cases()):
            run = run_case(adb, serial, remote_dir, target, fmt, config)
            runs.append(run)
            status = "ok" if run["result_ok"] else "FAIL"
            log("   [%02d/%d] %-9s %-9s %-12s %s %s"
                % (index + 1, len(matrix_cases()), target, fmt, config, status,
                   ("" if run["result_ok"] else "%s/%s" % (run["fail_stage"],
                                                           run["fail_result"]))))
            if not run["result_ok"]:
                raise RuntimeError("case %s %s %s failed: exit=%s stage=%s result=%s\n%s"
                                   % (target, fmt, config, run["exit_code"],
                                      run["fail_stage"], run["fail_result"],
                                      run["stdout_tail"]))
        log("== pulling readbacks")
        pulled = pull_outputs(adb, serial, remote_dir, os.path.join(evidence_dir, "output"),
                              matrix_cases())
        log("== checking readbacks")
        check_code, records, summary = check(evidence_dir, verbose=True, collect=True)
        if check_code != 0:
            raise RuntimeError("checker reported failures; see %s" % evidence_dir)
        cross_checks = host_cpu_srgb_cross_check(evidence_dir, records)
        base_commit = run_process(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT)
        context = {
            "label": args.label,
            "base_commit": base_commit["stdout"].strip(),
            "one_command": "python3 tools/tests/vulkan-wrapper-render.py --run --label %s "
                           "--avd %s --port %d" % (args.label, args.avd, args.port),
            "label": args.label,
            "fixture": fixture, "shaders": shaders, "host": host,
            "cross_check": cross_check, "inputs": inputs, "decoded": decoded,
            "expected": expected, "input_match": salvaged_input_match(inputs),
            "records": records, "summary": summary, "runs": runs,
            "cross_checks": cross_checks,
            "emulator": {
                "avd": args.avd, "serial": serial, "port": args.port,
                "launch_command": (boot or {}).get("launch_command"),
                "boot_seconds": (boot or {}).get("boot_seconds"),
                "boot_observed": (boot or {}).get("boot_observed"),
                "log": emulator_log,
                "device_facts": device_facts,
            },
            "evidence_dir": evidence_dir,
            "emulator_log": emulator_log,
            "salvaged": load_json(args.salvaged) if os.path.exists(args.salvaged) else None,
            "reference_change": reference_change_text(cross_check),
            "confirmed": [
                "One command runs the whole device pipeline and produces checked evidence",
                "All %d matrix cases rendered and passed the checker" % len(records),
                "Wrapper compute/compute-view UNORM and sRGB sampling works through the "
                "graphics pipeline",
                "The known pre-patch host-cpu sRGB encode/linearise split is visible at "
                "sampling time",
            ],
            "open": [
                "Effective-driver/phone sampling (Adreno/KGSL) with real game textures",
                "Mip/array/sRGB sampler variants beyond the recorded matrix",
            ],
            "limitations": [
                "Software Vulkan (llvmpipe) emulator only; no physical GPU or phone",
                "Synthetic deterministic textures, not game assets",
                "Nearest-only sampler and a single fullscreen triangle; no depth, blending "
                "or mip filtering",
                "The expected images use the pinned host bcdec decoder as the independent "
                "reference (the salvaged offline run used the Python reference decoder); "
                "BC1 decodes are identical, BC3 differs on 194 of 4096 source bytes by "
                "1 LSB, which is why the byte-exact counts differ slightly from that run",
                "No performance claims",
            ],
        }
        receipt = build_receipt(args, context)
        write_json(args.evidence, receipt)
        duration = round(time.time() - started, 1)
        log("== one-command run complete in %.1fs: %d pass / %d fail / %d skipped, "
            "%d unique cases, %d aliases, %d byte-exact"
            % (duration, summary["passed"], summary["failed"], summary["skipped"],
               summary["unique_cases"], summary["aliases"], summary["byte_exact"]))
        log("== receipt: %s" % args.evidence)
        return 0
    except Exception as error:  # noqa: BLE001 - report the concrete failure
        print("FAIL %s: %s" % (type(error).__name__, error))
        return 1
    finally:
        if boot is not None and not args.keep:
            log("== killing emulator %s" % serial)
            if not kill_emulator(adb, serial):
                print("WARNING %s did not disappear from adb after emu kill" % serial)
            if port_in_use(args.port):
                print("WARNING port %d still listening after emu kill" % args.port)


def load_json(path):
    with open(path) as handle:
        return json.load(handle)


def salvaged_input_match(inputs):
    """Record whether the generated inputs match the salvaged run's hashes."""
    salvaged = load_json(DEFAULT_SALVAGED) if os.path.exists(DEFAULT_SALVAGED) else None
    match = {}
    for base, info in inputs.items():
        recorded = None
        if salvaged:
            recorded = (salvaged.get("run", {}).get("inputs", {})
                        .get("%s-%dx%d.bin" % (base, SOURCE_WIDTH, SOURCE_HEIGHT)))
        match[base] = {"sha256": info["sha256"], "salvaged_sha256": recorded,
                       "matches_salvaged_run": recorded == info["sha256"]}
    return match


def reference_change_text(cross_check):
    return {
        "salvaged_reference": "Python reference decoder in tools/tests/vulkan-wrapper-reference.py",
        "orchestrated_reference": "pinned host bcdec.h helper (MIT OR Unlicense), per the "
                                  "one-command brief",
        "bc1_differing_bytes": cross_check["bc1"]["differing_bytes"],
        "bc3_differing_bytes": cross_check["bc3"]["differing_bytes"],
        "bc3_max_channel_delta": cross_check["bc3"]["max_channel_delta"],
        "effect": "BC1 decoded data is byte-identical between the two references, so the BC1 "
                  "expected images are unchanged. BC3 differs on %d of %d decoded bytes by at "
                  "most %d LSB, so BC3 cases are compared with a slightly different expected "
                  "image; the render pipeline itself is unchanged."
                  % (cross_check["bc3"]["differing_bytes"], cross_check["bc3"]["bytes"],
                     cross_check["bc3"]["max_channel_delta"]),
    }


# -- device-free self-test --------------------------------------------------

def self_test(verbose=False):
    count = 0
    failures = []

    def check_case(name, condition, detail=""):
        nonlocal count
        count += 1
        print("%s %s%s" % ("PASS" if condition else "FAIL", name,
                           (" (%s)" % detail) if detail and not condition else ""))
        if not condition:
            failures.append(name)

    # expected-image derivation: nearest upscale of a 2x2 image to 4x4
    image = bytes([1, 1, 1, 1, 2, 2, 2, 2,
                   3, 3, 3, 3, 4, 4, 4, 4])
    upscaled = upscale_nearest(image, 2, 2, 4, 4)
    check_case("upscale_nearest copies each 2x2 source texel to a 2x2 block",
               upscaled[0:4] == bytes([1, 1, 1, 1]) and
               upscaled[4:8] == bytes([1, 1, 1, 1]) and
               upscaled[8:12] == bytes([2, 2, 2, 2]) and
               upscaled[32:36] == bytes([3, 3, 3, 3]) and
               upscaled[40:44] == bytes([4, 4, 4, 4]))
    # sRGB transfer function anchors
    check_case("srgb_to_linear_u8 anchors (0, 128, 255)",
               (srgb_to_linear_u8(0), srgb_to_linear_u8(128), srgb_to_linear_u8(255),
                srgb_to_linear_u8(1)) == (0, 55, 255, 0),
               "got %s" % ((srgb_to_linear_u8(0), srgb_to_linear_u8(128),
                            srgb_to_linear_u8(255), srgb_to_linear_u8(1)),))
    # linear image keeps alpha
    check_case("linear_image transforms RGB and preserves alpha",
               linear_image(bytes([128, 128, 128, 7])) == bytes(
                   [srgb_to_linear_u8(128), srgb_to_linear_u8(128), srgb_to_linear_u8(128), 7]))
    # derive_expected on a synthetic evidence input directory
    temp_root = tempfile.mkdtemp(prefix="vulkan-render-selftest-")
    try:
        input_dir = os.path.join(temp_root, "input")
        output_dir = os.path.join(temp_root, "output")
        os.makedirs(input_dir)
        os.makedirs(output_dir)
        decoded = bytes((index * 7) & 0xFF for index in range(SOURCE_WIDTH * SOURCE_HEIGHT * 4))
        write(os.path.join(input_dir, "bc1-decoded-32x32.bin"), decoded)
        write(os.path.join(input_dir, "rgba8-32x32.bin"), decoded)
        for base in ("bc1", "rgba8"):
            expected = upscale_nearest(decoded, SOURCE_WIDTH, SOURCE_HEIGHT)
            write(os.path.join(input_dir, "%s-expected-unorm-64x64.bin" % base), expected)
            write(os.path.join(input_dir, "%s-expected-srgb-encoded-64x64.bin" % base), expected)
            if base == "bc1":
                write(os.path.join(input_dir, "bc1-expected-srgb-linear-64x64.bin"),
                      upscale_nearest(linear_image(decoded), SOURCE_WIDTH, SOURCE_HEIGHT))
        derived = derive_expected(input_dir, "bc1", "bc1_srgb")
        check_case("derive_expected returns unorm/encoded/linear images",
                   derived["unorm"] == derived["srgb-encoded"] and
                   derived["srgb-linear"] != derived["unorm"])
        check_case("derive_expected omits the linear image for UNORM formats",
                   "srgb-linear" not in derive_expected(input_dir, "bc1", "bc1"))
        # naming parser: canonical and alias forms
        canonical = os.path.join(output_dir, "out-candidate-bc1-compute.bin")
        alias = os.path.join(output_dir, "out-bc1-candidate-compute.bin")
        write(canonical, read(os.path.join(input_dir, "bc1-expected-unorm-64x64.bin")))
        write(alias, read(canonical))
        write(os.path.join(output_dir, "out-system-rgba8-native.bin"),
              read(os.path.join(input_dir, "rgba8-expected-unorm-64x64.bin")))
        parsed = sorted(output_cases(output_dir))
        check_case("naming parser accepts target-format-config and alias forms",
                   parsed[0][0] == "candidate" and parsed[0][1] == "bc1" and
                   parsed[0][2] == "compute" and parsed[-1][0] == "system" and
                   parsed[-1][2] == "native", str(parsed))
        code, records, summary = check(temp_root, collect=True)
        check_case("checker passes canonical + byte-identical alias and collapses the alias",
                   code == 0 and summary["unique_cases"] == 2 and summary["aliases"] == 1,
                   str(summary))
        # conflicting alias must fail, never be silently collapsed
        payload = bytearray(read(alias))
        payload[0] ^= 0xFF
        write(alias, bytes(payload))
        code, _, summary = check(temp_root, collect=True)
        check_case("checker fails on a conflicting alias", code != 0)
        # unrecognised output name must fail honestly
        write(os.path.join(output_dir, "out-weird-name.bin"), b"\0" * 16)
        code, _, summary = check(temp_root, collect=True)
        check_case("checker fails on an unrecognised readback name", code != 0)
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)

    # matrix/case naming self-consistency
    cases = matrix_cases()
    check_case("matrix has 31 canonical cases", len(cases) == 31, str(len(cases)))
    names = [case_output_name(*case) for case in cases]
    check_case("matrix output names are unique and canonical",
               len(set(names)) == len(names) and all(
                   name.startswith("out-%s-%s-%s.bin" % case) for name, case
                   in zip(names, cases)))
    check_case("wrapper environment uses forced BCn emulation",
               case_environment("candidate", "host-cpu", "/data/local/tmp/x")
               ["FORCE_BCN_EMULATION"] == "1")
    check_case("system environment does not set wrapper switches",
               "FORCE_BCN_EMULATION" not in case_environment(
                   "system", "native", "/data/local/tmp/x"))
    print("self-test: %d checks, %d failed" % (count, len(failures)))
    return 0 if not failures else 1


def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)


RUNBOOK = """\
Sampling/render-pipeline probe runbook
=====================================
Fixture:  tools/tests/vulkan-wrapper-render.c  (usage: render LIBRARY FORMAT INPUT OUTPUT W H RW RH VERT_SPV FRAG_SPV)
Shaders:  tools/tests/vulkan-wrapper-render.vert / .frag  (glslangValidator -V --target-env vulkan1.2)
Checker:  tools/tests/vulkan-wrapper-render.py --dir <evidence>
One shot: tools/tests/vulkan-wrapper-render.py --run
          (generates inputs, decodes with host bcdec, compiles shaders, builds/stages
           the fixture, boots the AVD, runs the 31-case matrix, pulls and checks the
           readbacks and writes docs/evidence/2026-10-22-vulkan-wrapper-render-orchestrated.json)

1. Compile the fixture for Android ARM64 with the recorded NDK recipe:
   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang \\
     -std=c11 -O2 -g -Wall -Wextra -Werror -D_GNU_SOURCE \\
     -I<wrapper source include directory> tools/tests/vulkan-wrapper-render.c -ldl \\
     -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \\
     -o probe
2. Stage probe, baseline/candidate wrapper .so, auxiliary libraries, the two
   SPIR-V files and the deterministic BCn/RGBA8 inputs in one directory; push it
   to /data/local/tmp/ on the emulator (or phone).
3. Run each case with the wrapper environment (FORCE_BCN_EMULATION=1; add
   USE_IMAGE_VIEW=1 for compute-view cases and USE_CPU_BCN=all for host-CPU
   cases; omitted for the native system-loader control):
   LD_LIBRARY_PATH=<dir> PREFIX=<dir> WRAPPER_LOG_LEVEL=none WRAPPER_CMD_LOG_LEVEL=none \\
     ./probe render <library> <bc1|bc3|bc1_srgb|bc3_srgb|rgba8> <input> <out> 32 32 64 64 vert.spv frag.spv
4. Pull the readbacks to <evidence>/output, keep the independent decoded texture
   (<fmt>-decoded-32x32.bin) and the derived expected images under
   <evidence>/input/, and run the checker:
   python3 tools/tests/vulkan-wrapper-render.py --dir <evidence> --verbose
Expected conventions: wrapper compute/compute-view and the native control render
sRGB sources as linearised values (sampled as UNORM); the pre-patch host-CPU
fallback renders encoded values (known defect, fixed by
tools/vulkan-wrapper/srgb-storage.patch). RGBA8 controls must be byte-exact.
"""


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Check or run the offscreen sampling/render-pipeline probe.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--run", action="store_true",
                      help="run the whole device pipeline and write the evidence JSON")
    mode.add_argument("--self-test", dest="self_test", action="store_true",
                      help="device-free checks of derivation and the naming parser")
    mode.add_argument("--print-runbook", action="store_true", help="print the device runbook")
    parser.add_argument("--dir", help="evidence directory with input/ and output/ (offline check)")
    parser.add_argument("--verbose", action="store_true", help="print every case and step")
    # --run options
    parser.add_argument("--work", default=DEFAULT_WORK,
                        help="evidence/work directory outside the repository")
    parser.add_argument("--evidence", default=DEFAULT_EVIDENCE,
                        help="evidence JSON path written by --run")
    parser.add_argument("--label", default="api36-4k-one-command",
                        help="label recorded in the evidence JSON")
    parser.add_argument("--avd", default=DEFAULT_AVD, help="AVD to boot for --run")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="emulator port for --run")
    parser.add_argument("--serial", help="adb serial (default emulator-<port>)")
    parser.add_argument("--attach", action="store_true",
                        help="use an already-attached emulator instead of booting one")
    parser.add_argument("--keep", action="store_true", help="leave the emulator running")
    parser.add_argument("--overwrite", action="store_true",
                        help="replace a non-empty --work evidence directory")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--emulator", default=DEFAULT_EMULATOR)
    parser.add_argument("--ndk", default=DEFAULT_NDK)
    parser.add_argument("--wrapper-build", default=DEFAULT_WRAPPER_BUILD,
                        help="pinned wrapper build tree (candidate/baseline + aux libs)")
    parser.add_argument("--vulkan-include", default=DEFAULT_VULKAN_INCLUDE,
                        help="directory containing vulkan/vulkan.h for the fixture build")
    parser.add_argument("--bcdec", default=DEFAULT_BCDEC, help="pinned bcdec.h path")
    parser.add_argument("--glslang", default=DEFAULT_GLSLANG)
    parser.add_argument("--reference-tool", default=DEFAULT_REFERENCE_TOOL,
                        help="committed Python BC reference (deterministic input generation)")
    parser.add_argument("--salvaged", default=DEFAULT_SALVAGED,
                        help="earlier salvage receipt to compare against")
    parser.add_argument("--memory", type=int, default=2048, help="emulator RAM in MB for --run")
    parser.add_argument("--cores", type=int, default=2, help="emulator cores for --run")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test(verbose=args.verbose)
    if args.print_runbook:
        print(RUNBOOK)
        return 0
    if args.run:
        return run_mode(args)
    if args.dir:
        return check(args.dir, verbose=args.verbose)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
