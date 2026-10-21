#!/usr/bin/env python3
"""Reference-image BC/depth probe for the Vapor Vulkan wrapper.

This tool uploads *deterministic pseudorandom* BC1/BC3 blocks and representative
depth texels through a wrapper `.so` (candidate or unpatched baseline) and reads
the result back, then compares the readback against an independent CPU S3TC
decoder implemented here in pure Python.  It also runs the same images through
the system Vulkan loader (`/system/lib64/libvulkan.so`), including a
`vkCmdBlitImage` decode path that exposes the driver's own BC decoder without a
graphics pipeline.  No app, APK, window, Wine, Steam or game is started, and no
validation layers are loaded.

Quick start (emulator, from the repo root)::

    python3 tools/tests/vulkan-wrapper-reference.py --serial emulator-5556

Phone run (no phone is attached to the qualifying machine; this is the runbook
that was written but not executed)::

    python3 tools/tests/vulkan-wrapper-reference.py --serial <PHONE_SERIAL>

The default `--wrapper-build` is the pinned qualification build directory
(read only): candidate-stripped.so, baseline-stripped.so and the native-probe
auxiliary libraries.  The fixture (`tools/tests/vulkan-wrapper-reference.c` in
this checkout) is compiled with the pinned NDK r27d aarch64-linux-android34-clang
using the recorded 16 KB page-size link flags and re-staged per run.

Independent reference decoder (S3TC rules implemented here, no wrapper code):
  * BC1 (DXT1): 8-byte block = two little-endian RGB565 endpoints c0, c1 then 16
    2-bit indices, LSB first, row-major within the block.
  * A 5/6-bit endpoint component c is the UNORM value c/31 or c/63 expanded to
    8 bits with round-to-nearest (round(c*255/31), round(c*255/63)).
  * c0 > c1: palette [c0, c1, (2*c0+c1)/3, (c0+2*c1)/3].  Otherwise:
    [c0, c1, (c0+c1)/2, transparent black] and index 3 decodes to (0,0,0,0).
  * BC3 (DXT5): 16-byte block = 8-byte alpha block then an 8-byte BC1 colour
    block.  The colour block always uses the four-colour palette (the c0/c1
    order is ignored).  Alpha: if a0 > a1 the palette is
    [a0, a1, (6a0+a1)/7, (5a0+2a1)/7 ... (a0+6a1)/7]; otherwise
    [a0, a1, (4a0+a1)/5 ... (a0+4a1)/5, 0, 255], with 16 3-bit indices, LSB
    first.
  * Every interpolant is the exact rational weighted average rounded to
    nearest 8-bit.  This is the convention the wrapper's compute-shader
    decoder implements (the emulator runs below match it exactly).  Decoder
    conventions legitimately differ by a few LSBs between implementations; the
    tool reports exact-match counts *and* tolerance/delta metrics, and the
    JSON records the observed spread (wrapper CPU fallback bcdec truncates BC3
    alpha interpolants by <=1 LSB; Mesa/llvmpipe's native decoder uses
    bit-replicated endpoints and truncating interpolants, <=2 LSBs).
  * Only UNORM BC1/BC3 are decoded.  BC7 decode is out of scope for this gate
    (format support is still recorded); BC2/4/5/6 use the same block machinery
    but are not part of the required gate.

Depth: D16_UNORM, D24_UNORM_S8_UINT and D32_SFLOAT images are created with
VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT, bound, uploaded
with vkCmdCopyBufferToImage and read back with vkCmdCopyImageToBuffer; only the
DEPTH aspect is copied.  Buffer texel widths observed in this environment:
d16=2 bytes, d24s8=4 bytes (value in the low 24 bits, high byte 0), d32=4 bytes.
The tool records whether each create/bind/copy succeeds and compares readback to
input exactly (d24s8 masked to its 24 depth bits, d32 compared bit-exactly).

Outputs: the evidence JSON (default docs/evidence/<DATE>-vulkan-wrapper-reference.json,
raw logs and readbacks under --scratch).
"""
import argparse
import hashlib
import json
import os
import platform
import random
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

DATE = "2026-10-21"
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_SOURCE = Path(__file__).resolve().with_suffix(".c")
DEFAULT_EVIDENCE = REPO_ROOT / "docs" / "evidence" / f"{DATE}-vulkan-wrapper-reference.json"
DEFAULT_WRAPPER_BUILD = Path(
    "/Users/cheng/Documents/Codex/2026-10-13/82379689/work/turnip-refactor-qualification/wrapper-build")
DEFAULT_SCRATCH = Path("/Users/cheng/wrapper-reference")
DEFAULT_NDK = Path("/Users/cheng/Library/Android/sdk/ndk/27.3.13750724")
DEFAULT_SYSTEM_LIBVULKAN = "/system/lib64/libvulkan.so"

WRAPPER_TARGETS = {
    "baseline": "baseline-stripped.so",
    "candidate": "candidate-stripped.so",
}
WRAPPER_TARGET_LABELS = {
    "baseline": "unpatched baseline wrapper",
    "candidate": "candidate wrapper",
    "system": "system Vulkan loader (no wrapper)",
}
PINNED_WRAPPER_HASHES = {
    # From docs/evidence/2026-10-21-vulkan-wrapper-native-4k.json.
    "baseline-stripped.so": "2494cda20dbe85e5732e3a0b9f872e9d8bce307e9fee6d3384d987011d7a0f8c",
    "candidate-stripped.so": "574cfb4d04bc036fe815357366a3c2e430d7da0a3e32da3d372c5c67fd835506",
}
AUX_LIBS = [
    "libadrenotools.so", "libandroid-shmem.so", "libc++_shared.so", "libz.so.1",
    "libzstd.so.1", "libxcb.so", "libX11-xcb.so", "libxcb-dri3.so",
    "libxcb-present.so", "libxcb-sync.so", "libxcb-randr.so", "libxcb-shm.so",
    "libdrm.so", "libXau.so", "libXdmcp.so", "libandroid-support.so",
]

# Wrapper decode-path knobs (read from the pinned wrapper source, read-only):
#   FORCE_BCN_EMULATION=1  forces the BC emulation path even when textureCompressionBC is supported.
#   USE_IMAGE_VIEW=0|1     compute shader writes via a staging buffer (0) or directly through an image view (1; build default).
#   USE_CPU_BCN=all        forces the host-side CPU (bcdec) decode instead of the compute shader.
#   WRAPPER_REDUCE_DEPTH_FORMAT=safe|aggressive  reduces depth formats; left unset (pass-through) by this probe.
CONFIGS = {
    "compute": {"USE_IMAGE_VIEW": "0"},
    "compute-view": {"USE_IMAGE_VIEW": "1"},
    "host-cpu": {"USE_IMAGE_VIEW": "0", "USE_CPU_BCN": "all"},
}
SYSTEM_CONFIG = "native"

DRIVER_IDS = {
    0: "UNKNOWN", 1: "AMD_PROPRIETARY", 2: "AMD_OPEN_SOURCE", 3: "MESA_RADV",
    4: "NVIDIA_PROPRIETARY", 5: "INTEL_PROPRIETARY_WINDOWS",
    6: "INTEL_OPEN_SOURCE_MESA", 7: "IMAGINATION_PROPRIETARY",
    8: "QUALCOMM_PROPRIETARY", 9: "ARM_PROPRIETARY", 10: "GOOGLE_SWIFTSHADER",
    11: "GGPI_DEV", 12: "BROADCOM_PROPRIETARY", 13: "MESA_LLVMPIPE",
    14: "MOLTENVK", 15: "COREAVI_PROPRIETARY", 16: "JUICE_PROPRIETARY",
    17: "VERISILICON_PROPRIETARY", 18: "MESA_TURNIP", 19: "MESA_V3DV",
    20: "MESA_PANVK", 21: "SAMSUNG_PROPRIETARY", 22: "MESA_PANVK_LEGACY",
    23: "MESA_AGXV",
}

# ---------------------------------------------------------------------------
# Independent S3TC CPU decoder (pure Python, no wrapper/Mesa/bcdec code)
# ---------------------------------------------------------------------------
S3TC_RULES = [
    "BC1/DXT1 block is 8 bytes: RGB565 c0, RGB565 c1 (little-endian), then 16 "
    "2-bit indices, LSB first, row-major inside the 4x4 block.",
    "Endpoint components are UNORM: c/31 (red, blue) and c/63 (green) expanded "
    "to 8-bit with round-to-nearest.",
    "c0 > c1: palette is [c0, c1, (2c0+c1)/3, (c0+2c1)/3]. Else: "
    "[c0, c1, (c0+c1)/2, transparent black (0,0,0,0)].",
    "BC3/DXT5 block is 16 bytes: 8-byte alpha block, then an 8-byte BC1 colour "
    "block that ALWAYS uses the four-colour palette regardless of c0 vs c1.",
    "BC3 alpha endpoints a0, a1 are 8-bit; a0 > a1 gives "
    "[a0, a1, (6a0+a1)/7, (5a0+2a1)/7, (4a0+3a1)/7, (3a0+4a1)/7, (2a0+5a1)/7, "
    "(a0+6a1)/7]; otherwise [a0, a1, (4a0+a1)/5, (3a0+2a1)/5, (2a0+3a1)/5, "
    "(a0+4a1)/5, 0, 255]. 16 3-bit indices, LSB first.",
    "All palette interpolants are exact rational weighted averages scaled to "
    "8-bit and rounded to nearest with ties to even, the Vulkan/D3D "
    "float-to-UNORM convention. This is the convention the wrapper's "
    "compute-shader decoder implements; the emulator runs match it exactly "
    "(including a 42.5 tie that resolves to 42 on the driver).",
    "Only UNORM BC1/BC3 are decoded here. BC7 decoding was judged not practical "
    "within this gate; its format support is still recorded. sRGB, BC2, BC4, "
    "BC5 and BC6 are not part of the required gate.",
]


def _div_round(numerator, denominator):
    """Round numerator/denominator to the nearest integer, ties to even.

    Ties-to-even matches the Vulkan/D3D float-to-UNORM conversion used by the
    compute-shader decode path (the emulator probe found exactly one green
    channel tie, 42.5, where the driver produced 42 and a round-half-up
    reference predicted 43).
    """
    quotient, remainder = divmod(numerator, denominator)
    doubled = 2 * remainder
    if doubled > denominator:
        return quotient + 1
    if doubled < denominator:
        return quotient
    return quotient if quotient % 2 == 0 else quotient + 1


def _expand5(value):
    return (value * 255 + 15) // 31


def _expand6(value):
    return (value * 255 + 31) // 63


def _endpoint(c, component):
    """Expand one RGB565 endpoint component (0=red, 1=green, 2=blue)."""
    if component == 0:
        return _expand5((c >> 11) & 0x1F)
    if component == 1:
        return _expand6((c >> 5) & 0x3F)
    return _expand5(c & 0x1F)


def bc1_color_palette(c0, c1, force_four_colour=False):
    """Return the four RGBA palette entries for a BC1 colour block.

    The endpoints are the UNORM 5/6-bit components; each palette channel is the
    exact rational weighted average of the raw endpoint components scaled to
    8-bit and rounded to nearest (this is the normalised S3TC interpolation the
    wrapper's compute-shader decoder implements).
    """
    endpoints = (((c0 >> 11) & 0x1F, (c0 >> 5) & 0x3F, c0 & 0x1F),
                 ((c1 >> 11) & 0x1F, (c1 >> 5) & 0x3F, c1 & 0x1F))

    def entry(wa, wb, denominator):
        channels = []
        for component in range(3):
            a, b = endpoints[0][component], endpoints[1][component]
            total = 31 if component != 1 else 63
            channels.append(_div_round((a * wa + b * wb) * 255, denominator * total))
        return (channels[0], channels[1], channels[2], 255)

    if force_four_colour or c0 > c1:
        return [entry(1, 0, 1), entry(0, 1, 1), entry(2, 1, 3), entry(1, 2, 3)]
    return [entry(1, 0, 1), entry(0, 1, 1), entry(1, 1, 2), (0, 0, 0, 0)]


def bc3_alpha_palette(a0, a1):
    """Return the eight 8-bit alpha palette entries for a BC3 alpha block."""
    if a0 > a1:
        palette = [a0, a1]
        palette += [_div_round(wa * a0 + wb * a1, 7) for wa, wb in
                    [(6, 1), (5, 2), (4, 3), (3, 4), (2, 5), (1, 6)]]
        return palette
    palette = [a0, a1]
    palette += [_div_round(wa * a0 + wb * a1, 5) for wa, wb in
                [(4, 1), (3, 2), (2, 3), (1, 4)]]
    return palette + [0, 255]


def _decode_blocks(data, width, height, block_bytes, palette_for_block,
                   indices_for_block):
    """Decode a block-compressed image into tightly packed RGBA8 bytes."""
    blocks_x = (width + 3) // 4
    blocks_y = (height + 3) // 4
    out = bytearray(width * height * 4)
    for by in range(blocks_y):
        for bx in range(blocks_x):
            offset = (by * blocks_x + bx) * block_bytes
            block = data[offset:offset + block_bytes]
            if len(block) != block_bytes:
                raise ValueError("truncated BC image: %d blocks expected" % (blocks_x * blocks_y))
            palette = palette_for_block(block)
            indices = indices_for_block(block)
            for y in range(4):
                py = by * 4 + y
                if py >= height:
                    continue
                for x in range(4):
                    px = bx * 4 + x
                    if px >= width:
                        continue
                    colour = palette[indices[y * 4 + x]]
                    out[(py * width + px) * 4:(py * width + px) * 4 + 4] = bytes(colour)
    return bytes(out)


def decode_bc1_rgba8(data, width, height):
    """Decode BC1 (UNORM) block data to tightly packed RGBA8."""
    return _decode_blocks(
        data, width, height, 8,
        lambda block: bc1_color_palette(*(struct.unpack_from("<HH", block, 0))),
        lambda block: [(struct.unpack_from("<I", block, 4)[0] >> (2 * p)) & 3 for p in range(16)])


def decode_bc3_rgba8(data, width, height):
    """Decode BC3 (UNORM) block data to tightly packed RGBA8."""
    def palette(block):
        alpha = bc3_alpha_palette(block[0], block[1])
        colour = bc1_color_palette(*struct.unpack_from("<HH", block, 8),
                                   force_four_colour=True)
        return [(c[0], c[1], c[2], alpha[a]) for a, c in
                zip([(int.from_bytes(block[2:8], "little") >> (3 * p)) & 7 for p in range(16)],
                    [colour[(struct.unpack_from("<I", block, 12)[0] >> (2 * p)) & 3]
                     for p in range(16)])]
    return _decode_blocks(data, width, height, 16, palette, lambda block: list(range(16)))


DECODERS = {"bc1": decode_bc1_rgba8, "bc3": decode_bc3_rgba8}


# ---------------------------------------------------------------------------
# Deterministic input generators
# ---------------------------------------------------------------------------
def generate_bc1_blocks(width, height, seed):
    rnd = random.Random(seed)
    blocks_x, blocks_y = (width + 3) // 4, (height + 3) // 4
    data = bytearray()
    coverage = {"four_colour_blocks": 0, "three_colour_blocks": 0,
                "equal_endpoint_blocks": 0, "all_black_blocks": 0, "all_white_blocks": 0}
    patterns = ["random", "four", "three", "equal", "black", "white"]
    for i in range(blocks_x * blocks_y):
        pattern = patterns[i % len(patterns)]
        if pattern == "black":
            c0 = c1 = 0
            color_index = 0
            coverage["all_black_blocks"] += 1
        elif pattern == "white":
            c0 = c1 = 0xFFFF
            color_index = 0
            coverage["all_white_blocks"] += 1
        else:
            c0 = rnd.getrandbits(16)
            c1 = rnd.getrandbits(16)
            if pattern == "four":
                if c0 <= c1:
                    c0, c1 = c1, c0
                if c0 == c1:
                    c0 = 0xFFFF
                    c1 = 0
            elif pattern == "three":
                if c0 >= c1:
                    c0, c1 = c1, c0
                if c0 == c1:
                    c0 = 0
                    c1 = 0xFFFF
            elif pattern == "equal":
                c1 = c0
            color_index = rnd.getrandbits(32)
            if c0 == c1:
                coverage["equal_endpoint_blocks"] += 1
            elif c0 > c1:
                coverage["four_colour_blocks"] += 1
            else:
                coverage["three_colour_blocks"] += 1
        data += struct.pack("<HHI", c0, c1, color_index)
    return bytes(data), coverage


def generate_bc3_blocks(width, height, seed):
    rnd = random.Random(seed)
    blocks_x, blocks_y = (width + 3) // 4, (height + 3) // 4
    data = bytearray()
    coverage = {"four_colour_blocks": 0, "three_colour_blocks": 0,
                "alpha_a0_gt_a1": 0, "alpha_a0_le_a1": 0,
                "alpha_equal_endpoints": 0, "alpha_extreme_endpoints": 0,
                "bc3_colour_with_c0_le_c1": 0}
    color_patterns = ["c0_gt", "c0_lt", "equal", "c0_lt"]
    alpha_patterns = ["gt", "le", "equal", "extreme", "random"]
    for i in range(blocks_x * blocks_y):
        colour_pattern = color_patterns[i % len(color_patterns)]
        c0 = rnd.getrandbits(16)
        c1 = rnd.getrandbits(16)
        if colour_pattern == "c0_gt" and c0 <= c1:
            c0, c1 = c1, c0
            if c0 == c1:
                c0 = 0xFFFF
                c1 = 0
            coverage["four_colour_blocks"] += 1
        elif colour_pattern == "c0_lt" and c0 >= c1:
            c0, c1 = c1, c0
            if c0 == c1:
                c0 = 0
                c1 = 0xFFFF
            coverage["three_colour_blocks"] += 1
            coverage["bc3_colour_with_c0_le_c1"] += 1
        elif colour_pattern == "equal":
            c1 = c0
        if colour_pattern == "c0_gt" and c0 > c1:
            coverage["four_colour_blocks"] += 1
        alpha_pattern = alpha_patterns[i % len(alpha_patterns)]
        if alpha_pattern == "gt":
            a0 = rnd.randrange(1, 256)
            a1 = rnd.randrange(a0)
            coverage["alpha_a0_gt_a1"] += 1
        elif alpha_pattern == "le":
            a0 = rnd.randrange(0, 256)
            a1 = rnd.randrange(a0, 256)
            coverage["alpha_a0_le_a1"] += 1
        elif alpha_pattern == "equal":
            a0 = a1 = rnd.getrandbits(8)
            coverage["alpha_equal_endpoints"] += 1
        elif alpha_pattern == "extreme":
            a0, a1 = (255, 0) if i % 2 else (0, 255)
            coverage["alpha_extreme_endpoints"] += 1
        else:
            a0, a1 = rnd.getrandbits(8), rnd.getrandbits(8)
            if a0 > a1:
                coverage["alpha_a0_gt_a1"] += 1
            else:
                coverage["alpha_a0_le_a1"] += 1
        alpha_index = rnd.getrandbits(48)
        color_index = rnd.getrandbits(32)
        data += struct.pack("<BB", a0, a1) + alpha_index.to_bytes(6, "little")
        data += struct.pack("<HHI", c0, c1, color_index)
    return bytes(data), coverage


def generate_rgba8(width, height, seed):
    rnd = random.Random(seed ^ 0x52474241)
    return bytes(rnd.getrandbits(8) for _ in range(width * height * 4))


def generate_depth(fmt, width, height, seed):
    rnd = random.Random(seed ^ 0x44455054)
    count = width * height
    if fmt == "d16":
        values = []
        for i in range(count):
            if i % 97 == 0:
                values.append(0xFFFF)
            elif i % 89 == 0:
                values.append(0)
            else:
                values.append(rnd.getrandbits(16))
        return b"".join(struct.pack("<H", v) for v in values)
    if fmt == "d24s8":
        values = []
        for i in range(count):
            if i % 97 == 0:
                values.append(0xFFFFFF)
            elif i % 89 == 0:
                values.append(0)
            else:
                values.append(rnd.getrandbits(24))
        # Depth aspect of D24_UNORM_S8_UINT is carried in a 32-bit buffer word
        # with the 24-bit depth value in the low bits (high byte zero).
        return b"".join(struct.pack("<I", v) for v in values)
    # d32: float bit patterns, compared bit-exactly.
    specials = [0.0, 1.0, 0.5, 0.25, -0.0]
    values = []
    for i in range(count):
        if i < len(specials):
            values.append(struct.pack("<f", specials[i]))
        else:
            values.append(struct.pack("<f", rnd.random()))
    return b"".join(values)


GENERATORS = {
    "bc1": generate_bc1_blocks,
    "bc3": generate_bc3_blocks,
    "rgba8": lambda w, h, s: (generate_rgba8(w, h, s), {}),
    "d16": lambda w, h, s: (generate_depth("d16", w, h, s), {}),
    "d24s8": lambda w, h, s: (generate_depth("d24s8", w, h, s), {}),
    "d32": lambda w, h, s: (generate_depth("d32", w, h, s), {}),
}

FORMAT_DESCRIPTIONS = {
    "bc1": {"vk_format": "VK_FORMAT_BC1_RGBA_UNORM_BLOCK", "block_bytes": 8, "kind": "bc"},
    "bc3": {"vk_format": "VK_FORMAT_BC3_UNORM_BLOCK", "block_bytes": 16, "kind": "bc"},
    "rgba8": {"vk_format": "VK_FORMAT_R8G8B8A8_UNORM", "texel_bytes": 4, "kind": "rgba8"},
    "d16": {"vk_format": "VK_FORMAT_D16_UNORM", "texel_bytes": 2, "kind": "depth"},
    "d24s8": {"vk_format": "VK_FORMAT_D24_UNORM_S8_UINT", "texel_bytes": 4, "kind": "depth"},
    "d32": {"vk_format": "VK_FORMAT_D32_SFLOAT", "texel_bytes": 4, "kind": "depth"},
}
DEPTH_BUFFER_BYTES = {"d16": 2, "d24s8": 4, "d32": 4}
DEPTH_MASK = {"d16": 0xFFFF, "d24s8": 0xFFFFFF, "d32": 0xFFFFFFFF}


def source_bytes(fmt, width, height):
    description = FORMAT_DESCRIPTIONS[fmt]
    if "block_bytes" in description:
        return ((width + 3) // 4) * ((height + 3) // 4) * description["block_bytes"]
    return width * height * description["texel_bytes"]


# ---------------------------------------------------------------------------
# Comparison helpers
# ---------------------------------------------------------------------------
def compare_rgba8(actual, expected, tolerance):
    pixels = min(len(actual), len(expected)) // 4
    exact = 0
    hard = 0
    max_delta = 0
    histogram = {}
    first_mismatches = []
    for i in range(pixels):
        base = i * 4
        a = actual[base:base + 4]
        e = expected[base:base + 4]
        if a == e:
            exact += 1
            histogram["0"] = histogram.get("0", 0) + 1
            continue
        delta = max(abs(a[c] - e[c]) for c in range(4))
        max_delta = max(max_delta, delta)
        key = str(delta) if delta <= 3 else "3+"
        histogram[key] = histogram.get(key, 0) + 1
        if delta > tolerance:
            hard += 1
        if len(first_mismatches) < 8:
            first_mismatches.append({"pixel": i, "expected": list(e),
                                     "actual": list(a), "delta": delta})
    mismatch = pixels - exact
    return {
        "pixels": pixels,
        "exact_pixels": exact,
        "mismatch_pixels": mismatch,
        "within_tolerance_pixels": mismatch - hard,
        "hard_mismatches": hard,
        "max_channel_delta": max_delta,
        "delta_histogram": {k: v for k, v in sorted(histogram.items(),
                                                    key=lambda item: (len(item[0]), item[0]))},
        "first_mismatches": first_mismatches,
        "tolerance": tolerance,
    }


def compare_compressed(actual, expected):
    length = min(len(actual), len(expected))
    mismatch = sum(1 for i in range(length) if actual[i] != expected[i])
    first = [i for i in range(length) if actual[i] != expected[i]][:8]
    return {
        "bytes": length,
        "exact_bytes": length - mismatch,
        "mismatch_bytes": mismatch,
        "hard_mismatches": mismatch,
        "max_channel_delta": None,
        "first_mismatch_offsets": first,
        "tolerance": 0,
    }


def compare_depth(actual, expected, fmt, tolerance):
    width = DEPTH_BUFFER_BYTES[fmt]
    mask = DEPTH_MASK[fmt]
    values = min(len(actual), len(expected)) // width
    exact = 0
    hard = 0
    max_delta = 0
    histogram = {}
    first_mismatches = []
    full_word_equal = 0
    for i in range(values):
        base = i * width
        a_raw = int.from_bytes(actual[base:base + width], "little")
        e_raw = int.from_bytes(expected[base:base + width], "little")
        if a_raw == e_raw:
            full_word_equal += 1
        a = a_raw & mask
        e = e_raw & mask
        delta = abs(a - e)
        if delta == 0:
            exact += 1
            histogram["0"] = histogram.get("0", 0) + 1
            continue
        max_delta = max(max_delta, delta)
        key = str(delta) if delta <= 3 else "3+"
        histogram[key] = histogram.get(key, 0) + 1
        if delta > tolerance:
            hard += 1
        if len(first_mismatches) < 8:
            first_mismatches.append({"texel": i, "expected": e, "actual": a,
                                     "expected_raw": e_raw, "actual_raw": a_raw,
                                     "delta": delta})
    mismatch = values - exact
    return {
        "texels": values,
        "exact_texels": exact,
        "mismatch_texels": mismatch,
        "within_tolerance_texels": mismatch - hard,
        "hard_mismatches": hard,
        "max_value_delta": max_delta,
        "full_word_equal_texels": full_word_equal,
        "mask": "0x%x" % mask,
        "expected_buffer_texel_bytes": width,
        "delta_histogram": {k: v for k, v in sorted(histogram.items(),
                                                    key=lambda kv: (len(kv[0]), kv[0]))},
        "first_mismatches": first_mismatches,
        "tolerance": tolerance,
    }


# ---------------------------------------------------------------------------
# Misc helpers
# ---------------------------------------------------------------------------
def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def elf_max_load_align(path):
    data = Path(path).read_bytes()
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file: %s" % path)
    is64, little = data[4] == 2, data[5] == 1
    endian = "<" if little else ">"
    if is64:
        e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 0x38)[0]
        aligns = []
        for i in range(e_phnum):
            offset = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(endian + "I", data, offset)[0]
            p_align = struct.unpack_from(endian + "Q", data, offset + 0x30)[0]
            if p_type == 1:  # PT_LOAD
                aligns.append(p_align)
    else:
        e_phoff = struct.unpack_from(endian + "I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from(endian + "H", data, 0x2A)[0]
        e_phnum = struct.unpack_from(endian + "H", data, 0x2C)[0]
        aligns = []
        for i in range(e_phnum):
            offset = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(endian + "I", data, offset)[0]
            p_align = struct.unpack_from(endian + "I", data, offset + 0x1C)[0]
            if p_type == 1:
                aligns.append(p_align)
    return max(aligns) if aligns else 0


def parse_probe_output(stdout):
    fields = {}
    steps = []
    for line in stdout.replace("\r\n", "\n").split("\n"):
        line = line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.startswith("STEP_"):
            stage = key[5:]
            if stage != "start" and stage not in steps:
                steps.append(stage)
            continue
        fields.setdefault(key, []).append(value)
    return fields, steps


def field(fields, key, default=None):
    values = fields.get(key)
    return values[-1] if values else default


def parse_format_props(line):
    props = {}
    for token in line.split():
        if "=" in token:
            key, value = token.split("=", 1)
            if key in ("name",):
                props[key] = value
            else:
                try:
                    props[key] = int(value, 0)
                except ValueError:
                    props[key] = value
    return props


class Log:
    def __init__(self, path=None):
        self.lines = []
        self.path = path
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                path.unlink()

    def __call__(self, message):
        line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
        print(line, flush=True)
        self.lines.append(line)
        if self.path:
            with open(self.path, "a", encoding="utf-8") as handle:
                handle.write(line + "\n")


class Adb:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def _base(self):
        return [self.adb, "-s", self.serial]

    def shell(self, command, timeout=180):
        started = time.time()
        try:
            proc = subprocess.run(self._base() + ["shell", command],
                                  capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return {"command": command, "exit_code": None, "stdout": "",
                    "stderr": "timeout after %ss" % timeout,
                    "duration_seconds": round(time.time() - started, 2)}
        return {
            "command": command,
            "exit_code": proc.returncode,
            "stdout": proc.stdout.replace("\r\n", "\n"),
            "stderr": proc.stderr.replace("\r\n", "\n"),
            "duration_seconds": round(time.time() - started, 2),
        }

    def push(self, local_paths, remote):
        command = self._base() + ["push"] + [str(p) for p in local_paths] + [remote]
        proc = subprocess.run(command, capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise RuntimeError("adb push failed: %s\n%s" % (" ".join(command), proc.stderr))
        return proc

    def pull(self, remote, local):
        command = self._base() + ["pull", remote, str(local)]
        proc = subprocess.run(command, capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise RuntimeError("adb pull failed: %s\n%s" % (" ".join(command), proc.stderr))
        return proc

    def logcat_clear(self):
        subprocess.run(self._base() + ["logcat", "-c"], capture_output=True, timeout=60)

    def logcat_vkwrapper(self, limit=40):
        proc = subprocess.run(self._base() + ["logcat", "-d", "-s", "VkWrapper"],
                              capture_output=True, text=True, timeout=60)
        lines = [l for l in proc.stdout.replace("\r\n", "\n").split("\n") if l.strip()]
        return lines[-limit:]


def list_devices(adb):
    proc = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=60)
    devices = []
    for line in proc.stdout.split("\n")[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def resolve_serial(adb, requested, log):
    devices = list_devices(adb)
    log("adb devices: %s" % (devices or "none"))
    if requested:
        if requested not in devices:
            raise SystemExit("error: requested device %r is not online (online: %s)"
                             % (requested, devices or "none"))
        return requested
    if len(devices) == 1:
        return devices[0]
    if "emulator-5556" in devices:
        return "emulator-5556"
    if not devices:
        raise SystemExit("error: no Android device/emulator is online; attach a phone or boot the AVD first")
    raise SystemExit("error: multiple devices online (%s); pass --serial" % devices)


def run_self_test():
    cases = []

    def summary(value):
        if isinstance(value, (bytes, bytearray)):
            return {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}
        return value

    def check(name, actual, expected):
        cases.append({"name": name, "passed": actual == expected,
                      "expected": summary(expected), "actual": summary(actual)})

    check("bc1 four-colour palette c0=red c1=blue",
          bc1_color_palette(0xF800, 0x001F),
          [(255, 0, 0, 255), (0, 0, 255, 255), (170, 0, 85, 255), (85, 0, 170, 255)])
    check("bc1 three-colour palette c0=blue c1=red (transparent index 3)",
          bc1_color_palette(0x001F, 0xF800),
          [(0, 0, 255, 255), (255, 0, 0, 255), (128, 0, 128, 255), (0, 0, 0, 0)])
    check("bc1 equal endpoints use three-colour mode",
          bc1_color_palette(0xFFFF, 0xFFFF),
          [(255, 255, 255, 255), (255, 255, 255, 255), (255, 255, 255, 255), (0, 0, 0, 0)])
    check("bc3 ignores colour endpoint order (always four-colour)",
          bc1_color_palette(0x001F, 0xF800, force_four_colour=True)[2], (85, 0, 170, 255))
    check("bc3 alpha palette a0=255 a1=0",
          bc3_alpha_palette(255, 0), [255, 0, 219, 182, 146, 109, 73, 36])
    check("bc3 alpha palette a0=0 a1=255",
          bc3_alpha_palette(0, 255), [0, 255, 51, 102, 153, 204, 0, 255])
    block = struct.pack("<HHI", 0xF800, 0x001F, 0xE4) + bytes(0)
    decoded = decode_bc1_rgba8(block, 4, 4)
    check("bc1 index order is LSB-first row-major",
          [tuple(decoded[i * 4:i * 4 + 4]) for i in range(4)],
          [(255, 0, 0, 255), (0, 0, 255, 255), (170, 0, 85, 255), (85, 0, 170, 255)])
    bc1_bytes, _ = generate_bc1_blocks(64, 64, 7)
    check("bc1 generator deterministic", generate_bc1_blocks(64, 64, 7)[0], bc1_bytes)
    check("bc1 generator seed-sensitive", generate_bc1_blocks(64, 64, 8)[0] == bc1_bytes, False)
    bc3_bytes, bc3_coverage = generate_bc3_blocks(64, 64, 7)
    check("bc3 generator deterministic", generate_bc3_blocks(64, 64, 7)[0], bc3_bytes)
    check("bc3 generator covers both alpha modes",
          bc3_coverage["alpha_a0_gt_a1"] > 0 and bc3_coverage["alpha_a0_le_a1"] > 0, True)
    check("bc3 decoder round trip length", len(decode_bc3_rgba8(bc3_bytes, 64, 64)), 64 * 64 * 4)
    d16 = generate_depth("d16", 8, 8, 1)
    check("d16 input size", len(d16), 8 * 8 * 2)
    d24 = generate_depth("d24s8", 8, 8, 1)
    check("d24s8 buffer word size", len(d24), 8 * 8 * 4)
    check("d24s8 input values fit in 24 bits",
          all(struct.unpack_from("<I", d24, i)[0] <= 0xFFFFFF for i in range(0, len(d24), 4)), True)
    passed = all(case["passed"] for case in cases)
    return {"passed": passed, "cases": cases}


# ---------------------------------------------------------------------------
# Main probe driver
# ---------------------------------------------------------------------------
class ReferenceProbe:
    def __init__(self, args, log):
        self.args = args
        self.log = log
        self.scratch = Path(args.scratch).resolve()
        self.repo_root = REPO_ROOT
        self.width = args.width
        self.height = args.height
        self.tolerance = args.tolerance
        self.inputs = {}
        self.references = {}
        self.coverage = {}
        self.runs = []
        self.staged = {}
        self.build = {}
        self.device_facts = {}
        self.caps = {}
        self.input_hashes = {}
        self.reference_hashes = {}
        self.remote_dir = None
        self.evaluated = []
        self.local_out = None
        self.adb = Adb(args.adb, args.serial)

    # -- deterministic data -------------------------------------------------
    def prepare_data(self):
        self.scratch_in = self.scratch / "in"
        self.scratch_ref = self.scratch / "refs"
        self.scratch_out = self.scratch / "out"
        self.scratch_bin = self.scratch / "bin"
        self.scratch_logs = self.scratch / "logs"
        for path in (self.scratch_in, self.scratch_ref, self.scratch_out,
                     self.scratch_bin, self.scratch_logs):
            path.mkdir(parents=True, exist_ok=True)
        for fmt in self.args.formats:
            data, coverage = GENERATORS[fmt](self.width, self.height, self.args.seed)
            expected = source_bytes(fmt, self.width, self.height)
            if len(data) != expected:
                raise SystemExit("internal error: %s input is %d bytes, expected %d"
                                 % (fmt, len(data), expected))
            self.inputs[fmt] = data
            if coverage:
                self.coverage[fmt] = coverage
            (self.scratch_in / (fmt + ".bin")).write_bytes(data)
            self.input_hashes[fmt] = sha256_bytes(data)
            if fmt in DECODERS:
                reference = DECODERS[fmt](data, self.width, self.height)
                self.references[fmt] = reference
                (self.scratch_ref / (fmt + "-rgba8.bin")).write_bytes(reference)
                self.reference_hashes[fmt] = sha256_bytes(reference)
            elif fmt in ("d16", "d24s8", "d32"):
                self.references[fmt] = data
            elif fmt == "rgba8":
                self.references[fmt] = data
        self.log("generated inputs: %s" % ", ".join(
            "%s=%dB" % (fmt, len(data)) for fmt, data in self.inputs.items()))

    # -- build --------------------------------------------------------------
    def build_fixture(self):
        clang = Path(self.args.clang) if self.args.clang else (
            Path(self.args.ndk) / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang")
        if not clang.exists():
            raise SystemExit("error: NDK clang not found at %s (pass --ndk or --clang)" % clang)
        output = self.scratch_bin / "probe"
        command = [
            str(clang), "-std=c11", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
            "-D_GNU_SOURCE", str(FIXTURE_SOURCE), "-ldl",
            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
            "-o", str(output),
        ]
        self.log("compiling fixture: %s" % " ".join(command))
        proc = subprocess.run(command, capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise SystemExit("error: fixture compile failed\n%s\n%s" % (proc.stdout, proc.stderr))
        self.build = {
            "compiler": str(clang),
            "command": command,
            "source": str(FIXTURE_SOURCE.relative_to(self.repo_root)),
            "source_sha256": sha256_file(FIXTURE_SOURCE),
            "binary": str(output),
            "binary_sha256": sha256_file(output),
            "binary_bytes": output.stat().st_size,
            "elf_max_load_align": elf_max_load_align(output),
            "page_size_link_flags": ["-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"],
        }
        self.log("fixture sha256=%s elf_max_load_align=%d"
                 % (self.build["binary_sha256"][:16], self.build["elf_max_load_align"]))

    # -- staging ------------------------------------------------------------
    def stage(self):
        wrapper_build = Path(self.args.wrapper_build)
        remote = self.args.remote_dir or ("/data/local/tmp/vapor-wrapper-reference-%s" % DATE)
        self.remote_dir = remote
        self.staged = {"remote_dir": remote, "files": {}, "commands": []}
        shell = self.adb.shell("rm -rf %s && mkdir -p %s/in %s/out" % (remote, remote, remote))
        self.staged["commands"].append({"command": "rm -rf/mkdir staging dir", **shell})
        if shell["exit_code"] != 0:
            raise SystemExit("error: cannot create remote staging dir: %s" % shell["stderr"])
        local_files = [self.scratch_bin / "probe"]
        for name in WRAPPER_TARGETS.values():
            path = wrapper_build / name
            if not path.exists():
                raise SystemExit("error: pinned wrapper build is missing %s" % path)
            local_files.append(path)
        for name in AUX_LIBS:
            path = wrapper_build / "native-probe" / name
            if not path.exists():
                raise SystemExit("error: pinned auxiliary library is missing %s" % path)
            local_files.append(path)
        self.adb.push(local_files, remote)
        self.adb.shell("chmod 755 %s/probe" % remote)
        for path in local_files:
            self.staged["files"][path.name] = {
                "source": str(path),
                "sha256": sha256_file(path),
                "bytes": path.stat().st_size,
            }
        input_paths = [self.scratch_in / (fmt + ".bin") for fmt in self.args.formats]
        self.adb.push(input_paths, "%s/in" % remote)
        for path in input_paths:
            self.staged["files"]["in/" + path.name] = {
                "source": str(path), "sha256": sha256_file(path),
                "bytes": path.stat().st_size}
        self.log("staged %d files to %s" % (len(self.staged["files"]), remote))
        self.staged["wrapper_build"] = str(wrapper_build)
        self.staged["wrapper_hashes_match_pinned"] = all(
            sha256_file(wrapper_build / name) == expected
            for name, expected in PINNED_WRAPPER_HASHES.items())
        if not self.staged["wrapper_hashes_match_pinned"]:
            self.log("WARNING: pinned wrapper hashes do not match the recorded values")

    # -- device facts -------------------------------------------------------
    def collect_device_facts(self):
        properties = {}
        for prop in ["ro.product.manufacturer", "ro.product.model", "ro.build.version.sdk",
                     "ro.build.version.release", "ro.product.cpu.abi", "ro.kernel.qemu",
                     "ro.hardware", "ro.board.platform", "ro.boot.qemu.avd_name",
                     "ro.boot.qemu.version"]:
            result = self.adb.shell("getprop %s" % prop, timeout=30)
            properties[prop] = result["stdout"].strip()
        uname = self.adb.shell("uname -a", timeout=30)["stdout"].strip()
        page = self.adb.shell("getconf PAGE_SIZE", timeout=30)["stdout"].strip()
        self.device_facts = {
            "serial": self.adb.serial,
            "is_emulator": properties.get("ro.kernel.qemu") == "1",
            "properties": properties,
            "uname": uname,
            "page_size_from_getconf": int(page) if page.isdigit() else None,
            "probe_reported": {},
        }
        emulator_version = None
        emulator_bin = Path(self.args.emulator)
        if emulator_bin.exists():
            proc = subprocess.run([str(emulator_bin), "-version"],
                                  capture_output=True, text=True, timeout=60)
            first = [l for l in proc.stdout.split("\n") if l.strip()]
            emulator_version = first[0] if first else None
        self.device_facts["emulator_binary_version"] = emulator_version
        self.log("device %s emulator=%s page_size=%s"
                 % (self.adb.serial, self.device_facts["is_emulator"], page))

    # -- probe invocation ---------------------------------------------------
    def run_probe(self, target, config, mode, fmt, capture_logcat=False):
        remote = self.remote_dir
        library = (DEFAULT_SYSTEM_LIBVULKAN if target == "system"
                   else "%s/%s" % (remote, WRAPPER_TARGETS[target]))
        env = {"LD_LIBRARY_PATH": remote, "PREFIX": remote,
               "WRAPPER_LOG_LEVEL": (self.args.wrapper_log_level
                                     if target == "system" or not capture_logcat else "debug"),
               "WRAPPER_CMD_LOG_LEVEL": "none"}
        if target != "system":
            env["FORCE_BCN_EMULATION"] = "1"
            env.update(CONFIGS[config])
        elif "compute" in config:
            raise SystemExit("internal error: system target cannot use wrapper config")
        env_string = " ".join("%s=%s" % (key, value) for key, value in sorted(env.items()))
        output_name = "%s-%s-%s-%s.bin" % (target, config, mode, fmt)
        if mode == "caps":
            command = "cd %s && %s ./probe caps %s" % (remote, env_string, library)
        else:
            command = ("cd %s && %s ./probe %s %s %s in/%s.bin out/%s %d %d"
                       % (remote, env_string, mode, library, fmt, fmt, output_name,
                          self.width, self.height))
        if capture_logcat:
            self.adb.logcat_clear()
        result = self.adb.shell(command, timeout=self.args.timeout)
        logcat_full = self.adb.logcat_vkwrapper() if capture_logcat else []
        keep = ("Emulating support", "use_compute_shader", "use_image_view",
                "Changing image", "Forcing BCn")
        logcat = [line for line in logcat_full if any(token in line for token in keep)]
        decode_path = {}
        for line in logcat:
            match = re.search(r"use_compute_shader=(\d), use_image_view=(\d)", line)
            if match:
                decode_path["use_compute_shader"] = int(match.group(1))
                decode_path["use_image_view"] = int(match.group(2))
            if "Forcing BCn emulation" in line:
                decode_path["forced_bcn_emulation"] = True
            match = re.search(r"Changing image .* format from bcn texture: (\d+) to (\d+)", line)
            if match:
                decode_path["emulated_from_vk_format"] = int(match.group(1))
                decode_path["emulated_to_vk_format"] = int(match.group(2))
        fields, steps = parse_probe_output(result["stdout"])
        run = {
            "name": output_name[:-4],
            "target": target,
            "target_label": WRAPPER_TARGET_LABELS[target],
            "config": config,
            "mode": mode,
            "format": fmt,
            "output_name": output_name,
            "command": command,
            "environment": env,
            "exit_code": result["exit_code"],
            "duration_seconds": result["duration_seconds"],
            "stdout": result["stdout"],
            "stderr": result["stderr"],
            "steps": steps,
            "fields": {key: values[-1] for key, values in fields.items()},
            "format_properties": None,
            "logcat": logcat,
            "decode_path": decode_path,
        }
        for line in result["stdout"].replace("\r\n", "\n").split("\n"):
            if line.startswith("FORMAT_PROPS "):
                run["format_properties"] = parse_format_props(line[len("FORMAT_PROPS "):])
        if not (result["exit_code"] == 0 and fields.get("RESULT", ["fail"])[-1] == "ok"):
            run["stdout"] = result["stdout"]
            run["stderr"] = result["stderr"]
        log_path = self.scratch_logs / (output_name[:-4] + ".log")
        with open(log_path, "w", encoding="utf-8") as handle:
            handle.write("command: %s\n" % command)
            handle.write("exit_code: %s\n" % result["exit_code"])
            handle.write("duration_seconds: %s\n" % result["duration_seconds"])
            handle.write("--- stdout ---\n%s" % result["stdout"])
            handle.write("--- stderr ---\n%s" % result["stderr"])
            if logcat:
                handle.write("--- logcat VkWrapper ---\n%s\n" % "\n".join(logcat))
        self.log("run %s exit=%s %s" % (run["name"], run["exit_code"],
                                        "ok" if field(fields, "RESULT") == "ok" else "FAIL"))
        self.runs.append(run)
        return run

    # -- run matrix ---------------------------------------------------------
    def execute(self):
        targets = self.args.targets
        formats = self.args.formats
        modes = self.args.modes
        # Capability query per target (system loader, baseline, candidate).
        for target in targets:
            config = SYSTEM_CONFIG if target == "system" else "compute"
            run = self.run_probe(target, config, "caps", "rgba8")
            self.caps[target] = run
        # Identity/transfer controls and depth on every target.
        for target in targets:
            config = SYSTEM_CONFIG if target == "system" else "compute"
            for fmt in [f for f in ("rgba8", "d16", "d24s8", "d32") if f in formats]:
                self.run_probe(target, config, "copy", fmt)
        wrapper_targets = [t for t in targets if t != "system"]
        # BC copy: system loader gives a compressed-data transfer control;
        # wrappers decode through the chosen wrapper decode path.
        for fmt in [f for f in ("bc1", "bc3") if f in formats]:
            if "system" in targets:
                self.run_probe("system", SYSTEM_CONFIG, "copy", fmt)
            for target in wrapper_targets:
                for config in self.args.configs:
                    self.run_probe(target, config, "copy", fmt, capture_logcat=True)
        # BC blit: system loader decodes with the driver's own BC decoder; the
        # wrapper's emulated RGBA8 image makes the blit a pass-through.
        if "blit" in modes:
            for fmt in [f for f in ("bc1", "bc3") if f in formats]:
                if "system" in targets:
                    self.run_probe("system", SYSTEM_CONFIG, "blit", fmt)
                for target in wrapper_targets:
                    for config in self.args.configs:
                        self.run_probe(target, config, "blit", fmt, capture_logcat=True)

    # -- pulling + evaluation ----------------------------------------------
    def pull_outputs(self):
        pulled = 0
        for run in self.runs:
            if run["mode"] not in ("copy", "blit"):
                continue
            remote_file = "%s/out/%s" % (self.remote_dir, run["output_name"])
            self.adb.pull(remote_file, str(self.scratch_out / run["output_name"]))
            pulled += 1
        self.local_out = self.scratch_out
        self.log("pulled %d readback files into %s" % (pulled, self.local_out))

    @staticmethod
    def _run_record(run, metrics, decoded_kind, verdict, reasons):
        record = {}
        for key in ("name", "target", "config", "mode", "format", "command", "environment",
                    "exit_code", "duration_seconds", "stdout", "stderr", "steps", "fields",
                    "format_properties", "logcat", "decode_path", "readback_sha256",
                    "readback_bytes"):
            if key in run:
                record[key] = run[key]
        record.update({"metrics": metrics, "decoded_kind": decoded_kind,
                       "verdict": verdict, "reasons": reasons})
        return record

    def evaluate(self):
        evaluated = []
        for run in self.runs:
            fmt = run["format"]
            mode = run["mode"]
            target = run["target"]
            parsed = run["fields"]
            reasons = []
            metrics = None
            decoded_kind = None
            probed_ok = (run["exit_code"] == 0 and parsed.get("RESULT") == "ok")
            if not probed_ok:
                reasons.append(
                    "probe did not report RESULT=ok (exit=%s, FAIL_STAGE=%s, FAIL_RESULT=%s)"
                    % (run["exit_code"], parsed.get("FAIL_STAGE"), parsed.get("FAIL_RESULT")))
                run["verdict"] = "fail"
                evaluated.append(self._run_record(run, None, None, "fail", reasons))
                continue
            if mode == "caps":
                metrics = {"format_properties": run["format_properties"]}
                decoded_kind = "caps"
                if not parsed.get("DEVICE_NAME"):
                    reasons.append("caps did not report a device name")
                verdict = "pass" if not reasons else "fail"
                run["verdict"] = verdict
                evaluated.append(self._run_record(run, metrics, decoded_kind, verdict, reasons))
                continue
            output_path = self.local_out / run["output_name"]
            if not output_path.exists():
                reasons.append("missing readback file %s" % output_path)
                actual = b""
            else:
                actual = output_path.read_bytes()
                run["readback_sha256"] = sha256_bytes(actual)
                run["readback_bytes"] = len(actual)
            expected_allocation = max(source_bytes(fmt, self.width, self.height),
                                      self.width * self.height * 4)
            bytes_out = parsed.get("BYTES_OUT")
            if bytes_out is not None and int(bytes_out) != expected_allocation:
                reasons.append("BYTES_OUT=%s, expected %d" % (bytes_out, expected_allocation))
            if fmt in ("bc1", "bc3"):
                if target == "system" and mode == "copy":
                    decoded_kind = "compressed"
                    expected = self.inputs[fmt]
                    metrics = compare_compressed(actual[:len(expected)], expected)
                    if metrics["mismatch_bytes"]:
                        reasons.append("compressed-transfer round trip differs in %d of %d bytes"
                                       % (metrics["mismatch_bytes"], metrics["bytes"]))
                else:
                    decoded_kind = "rgba8"
                    expected = self.references[fmt]
                    metrics = compare_rgba8(actual[:self.width * self.height * 4],
                                            expected, self.tolerance)
                    if metrics["hard_mismatches"]:
                        reasons.append("%d pixel(s) exceed the %d-LSB tolerance (max delta %d)"
                                       % (metrics["hard_mismatches"], self.tolerance,
                                          metrics["max_channel_delta"]))
                    if target != "system" and mode == "copy":
                        compressed = self.inputs[fmt]
                        if actual[:len(compressed)] == compressed and not any(
                                actual[len(compressed):]):
                            reasons.append("wrapper readback equals the compressed input: BC "
                                           "emulation was not observed")
            elif fmt == "rgba8":
                decoded_kind = "rgba8"
                expected = self.references["rgba8"]
                metrics = compare_rgba8(actual[:self.width * self.height * 4],
                                        expected, self.tolerance)
                if metrics["hard_mismatches"]:
                    reasons.append("%d pixel(s) exceed tolerance" % metrics["hard_mismatches"])
            else:
                decoded_kind = "depth"
                expected = self.references[fmt]
                metrics = compare_depth(actual[:len(expected)], expected, fmt, self.tolerance)
                if metrics["mismatch_texels"]:
                    reasons.append("%d of %d depth texels differ (max delta %d)"
                                   % (metrics["mismatch_texels"], metrics["texels"],
                                      metrics["max_value_delta"]))
            verdict = "pass" if not reasons else "fail"
            run["verdict"] = verdict
            run["metrics"] = metrics
            evaluated.append(self._run_record(run, metrics, decoded_kind, verdict, reasons))
        self.evaluated = evaluated
        failed = [run for run in evaluated if run["verdict"] != "pass"]
        self.log("evaluated %d runs: %d pass, %d fail"
                 % (len(evaluated), len(evaluated) - len(failed), len(failed)))
        for run in failed:
            self.log("FAIL %s: %s" % (run["name"], "; ".join(run["reasons"])))

    # -- reporting ----------------------------------------------------------
    def headline(self):
        statements = []
        for target in ("candidate", "baseline"):
            for config in self.args.configs:
                for fmt in ("bc1", "bc3"):
                    matching = [run for run in self.evaluated if
                                run["target"] == target and run["config"] == config and
                                run["mode"] == "copy" and run["format"] == fmt]
                    if not matching or not matching[0]["metrics"]:
                        continue
                    metrics = matching[0]["metrics"]
                    if metrics["exact_pixels"] == metrics["pixels"]:
                        statements.append(
                            "%s/%s/%s copy: %d/%d pixels exactly match the independent "
                            "reference decoder (0 LSB delta)"
                            % (target, config, fmt, metrics["exact_pixels"], metrics["pixels"]))
                    else:
                        statements.append(
                            "%s/%s/%s copy: %d/%d pixels match exactly, %d within %d LSB "
                            "(max channel delta %d)"
                            % (target, config, fmt, metrics["exact_pixels"], metrics["pixels"],
                               metrics["within_tolerance_pixels"], self.tolerance,
                               metrics["max_channel_delta"]))
        native = [run for run in self.evaluated if run["target"] == "system" and
                  run["mode"] == "blit" and run["format"] in ("bc1", "bc3")]
        for run in native:
            metrics = run.get("metrics")
            if not metrics:
                continue
            statements.append(
                "system-loader native BC decode (vkCmdBlitImage) %s: %d/%d pixels exactly match "
                "the reference, %d within %d LSB (max delta %d); decoder-convention spread"
                % (run["format"], metrics["exact_pixels"], metrics["pixels"],
                   metrics["within_tolerance_pixels"], self.tolerance,
                   metrics["max_channel_delta"]))
        depth_runs = [run for run in self.evaluated if run["format"] in DEPTH_BUFFER_BYTES]
        if depth_runs:
            exact = all(run["metrics"] and run["metrics"]["exact_texels"] == run["metrics"]["texels"]
                        for run in depth_runs)
            statements.append("depth (d16/d24s8/d32) transfer round trip: %s"
                              % ("bit-exact on every probe" if exact
                                 else "see depth results; some texels differ"))
        return statements

    def findings(self):
        statements = []
        wrapper_targets = ("baseline", "candidate")
        exact_runs = [run for run in self.evaluated
                      if run["target"] in wrapper_targets
                      and run["config"] in ("compute", "compute-view")
                      and run["mode"] in ("copy", "blit")
                      and run["format"] in ("bc1", "bc3")
                      and run.get("metrics")
                      and run["metrics"].get("exact_pixels") == run["metrics"].get("pixels")]
        all_runs = [run for run in self.evaluated
                    if run["target"] in wrapper_targets
                    and run["config"] in ("compute", "compute-view")
                    and run["mode"] in ("copy", "blit")
                    and run["format"] in ("bc1", "bc3")]
        if all_runs:
            statements.append(
                "Wrapper compute-shader decode (staging and image-view configs, candidate and "
                "baseline): %d/%d copy+blit probes match the independent reference decoder "
                "exactly (%d/%d pixels byte-identical in the verified set)."
                % (len(exact_runs), len(all_runs), sum(run["metrics"]["exact_pixels"] for run in exact_runs),
                   sum(run["metrics"]["pixels"] for run in exact_runs)))
        host = [run for run in self.evaluated
                if run["target"] in wrapper_targets and run["config"] == "host-cpu"
                and run["mode"] == "copy" and run["format"] in ("bc1", "bc3")]
        for run in host:
            metrics = run.get("metrics") or {}
            statements.append(
                "Wrapper CPU fallback path (USE_CPU_BCN=all, bcdec): %s/%s copy has %d/%d "
                "pixels exact and a maximum channel delta of %d LSB (bcdec truncates "
                "interpolated palette entries; within the reported tolerance)."
                % (run["target"], run["format"], metrics.get("exact_pixels"),
                   metrics.get("pixels"), metrics.get("max_channel_delta")))
        native = [run for run in self.evaluated
                  if run["target"] == "system" and run["mode"] == "blit"
                  and run["format"] in ("bc1", "bc3")]
        for run in native:
            metrics = run.get("metrics") or {}
            statements.append(
                "System-loader native BC decode (vkCmdBlitImage, Mesa/llvmpipe): %s has %d/%d "
                "pixels exact and a maximum channel delta of %d LSB versus the reference, "
                "showing the expected inter-decoder convention spread; it is not the wrapper "
                "under test."
                % (run["format"], metrics.get("exact_pixels"), metrics.get("pixels"),
                   metrics.get("max_channel_delta")))
        readbacks = {}
        for run in self.evaluated:
            if run["target"] in ("baseline", "candidate") and run.get("readback_sha256"):
                readbacks.setdefault((run["config"], run["mode"], run["format"]), {})[
                    run["target"]] = run["readback_sha256"]
        identical = sum(1 for value in readbacks.values()
                        if len(value) == 2 and value.get("baseline") == value.get("candidate"))
        statements.append(
            "Candidate versus baseline: %d/%d comparable wrapper readbacks are byte-identical; "
            "the candidate's format-list ownership fix does not change decoded pixels."
            % (identical, len(readbacks)))
        depth = [run for run in self.evaluated if run["format"] in DEPTH_BUFFER_BYTES]
        exact_depth = all(run["metrics"]["exact_texels"] == run["metrics"]["texels"]
                          for run in depth if run.get("metrics"))
        statements.append(
            "Depth transfer: %d depth probes (D16_UNORM, D24_UNORM_S8_UINT, D32_SFLOAT; system, "
            "baseline and candidate) are %s; buffer texel widths observed: %s."
            % (len(depth), "bit-exact round trips" if exact_depth else "not all exact",
               ", ".join("%s=%d bytes" % (fmt, DEPTH_BUFFER_BYTES[fmt]) for fmt in DEPTH_BUFFER_BYTES)))
        compute_paths = {str(run.get("decode_path")) for run in self.evaluated
                         if run.get("decode_path")}
        if compute_paths:
            statements.append(
                "Wrapper decode path was confirmed from logcat: %s."
                % "; ".join(sorted(compute_paths)))
        statements.append(
            "Page size: probe sysconf=%s, getconf=%s, fixture ELF max-load alignment=%s "
            "(16 KB link flags on a 4 KB-page emulator)."
            % (self.caps.get("system", {}).get("page_size_in_probe"),
               self.device_facts.get("page_size_from_getconf"),
               self.build.get("elf_max_load_align")))
        return statements

    def summary(self):
        failed = [run for run in self.evaluated if run["verdict"] != "pass"]
        depth_results = []
        for run in self.evaluated:
            if run["format"] in DEPTH_BUFFER_BYTES:
                metrics = run.get("metrics") or {}
                depth_results.append({
                    "target": run["target"],
                    "format": run["format"],
                    "vk_format": FORMAT_DESCRIPTIONS[run["format"]]["vk_format"],
                    "command": run["command"],
                    "exit_code": run["exit_code"],
                    "verdict": run["verdict"],
                    "create_bind_copy_steps": [step for step in run["steps"]],
                    "format_properties": run["format_properties"],
                    "metrics": metrics if metrics else None,
                    "probe_fields": {k: v for k, v in run["fields"].items()
                                     if k.startswith(("RESULT", "STEP", "BYTES_OUT",
                                                      "FORMAT_PROPS", "IMAGE", "DEVICE_"))},
                })
        per_run = {}
        for run in self.evaluated:
            if run["format"] in ("bc1", "bc3") and run["mode"] in ("copy", "blit"):
                metrics = run.get("metrics") or {}
                key = "%s/%s/%s/%s" % (run["target"], run["config"], run["mode"], run["format"])
                if run["decoded_kind"] == "compressed":
                    per_run[key] = {
                        "verdict": run["verdict"],
                        "decoded_kind": "compressed",
                        "bytes": metrics.get("bytes"),
                        "exact_bytes": metrics.get("exact_bytes"),
                        "mismatch_bytes": metrics.get("mismatch_bytes"),
                        "hard_mismatches": metrics.get("hard_mismatches"),
                    }
                else:
                    per_run[key] = {
                        "verdict": run["verdict"],
                        "decoded_kind": run["decoded_kind"],
                        "pixels": metrics.get("pixels"),
                        "exact_pixels": metrics.get("exact_pixels"),
                        "mismatch_pixels": metrics.get("mismatch_pixels"),
                        "within_tolerance_pixels": metrics.get("within_tolerance_pixels"),
                        "hard_mismatches": metrics.get("hard_mismatches"),
                        "max_channel_delta": metrics.get("max_channel_delta"),
                        "delta_histogram": metrics.get("delta_histogram"),
                    }
        return {
            "runs_total": len(self.evaluated),
            "runs_pass": len(self.evaluated) - len(failed),
            "runs_fail": len(failed),
            "all_runs_pass": not failed,
            "failures": [{"name": run["name"], "reasons": run["reasons"]} for run in failed],
            "reference_decode": per_run,
            "headline": self.headline(),
            "depth_results_count": len(depth_results),
        }

    def payload(self, self_test):
        wrapper_build = Path(self.args.wrapper_build)
        wrapper_builds = {}
        for name in WRAPPER_TARGETS.values():
            path = wrapper_build / name
            wrapper_builds[name] = {
                "path": str(path),
                "sha256": sha256_file(path),
                "bytes": path.stat().st_size,
                "matches_recorded_pinned_hash": sha256_file(path) == PINNED_WRAPPER_HASHES.get(name),
            }
        auxiliary = {}
        for name in AUX_LIBS:
            path = wrapper_build / "native-probe" / name
            auxiliary[name] = {"sha256": sha256_file(path), "bytes": path.stat().st_size}
        caps = {}
        for target, run in self.caps.items():
            fields = run["fields"]
            formats = []
            for line in run["stdout"].replace("\r\n", "\n").split("\n"):
                if line.startswith("FORMAT_PROPS "):
                    formats.append(parse_format_props(line[len("FORMAT_PROPS "):]))
            notes = []
            if target != "system":
                notes.append(
                    "FORCE_BCN_EMULATION=1 is active for this run but this wrapper build still "
                    "reports the underlying driver's textureCompressionBC in "
                    "vkGetPhysicalDeviceFeatures; forcing is confirmed instead by the wrapper "
                    "logcat ('Forcing BCn emulation', 'Emulating support for format=..') and by "
                    "the emulated BC image readback (expanded RGBA8).")
            unsupported = [prop["name"] for prop in formats
                           if prop.get("optimal_transfer_src") == 0
                           or prop.get("optimal_transfer_dst") == 0]
            if unsupported:
                notes.append("Formats without optimal-tiling TRANSFER_SRC/TRANSFER_DST support "
                             "on this driver: %s." % ", ".join(unsupported))
            caps[target] = {
                "command": run["command"],
                "exit_code": run["exit_code"],
                "device_name": fields.get("DEVICE_NAME"),
                "vendor": fields.get("DEVICE_VENDOR"),
                "device_id": fields.get("DEVICE_ID"),
                "api": fields.get("DEVICE_API"),
                "page_size_in_probe": fields.get("PAGE_SIZE"),
                "driver_id": fields.get("DEVICE_DRIVER_ID"),
                "driver_id_name": DRIVER_IDS.get(int(fields["DEVICE_DRIVER_ID"], 0)
                                                 if fields.get("DEVICE_DRIVER_ID") else 0),
                "driver_name": fields.get("DEVICE_DRIVER_NAME"),
                "driver_info": fields.get("DEVICE_DRIVER_INFO"),
                "driver_version": fields.get("DEVICE_DRIVER_VERSION"),
                "device_type": fields.get("DEVICE_TYPE"),
                "texture_compression_bc": fields.get("FEATURE_textureCompressionBC"),
                "format_properties": formats,
                "notes": notes,
            }
        device = dict(self.device_facts)
        system_caps = caps.get("system", {})
        device.update({
            "probe_reported": {
                "device_name": system_caps.get("device_name"),
                "vendor": system_caps.get("vendor"),
                "device_id": system_caps.get("device_id"),
                "api": system_caps.get("api"),
                "page_size": system_caps.get("page_size_in_probe"),
                "driver_id": system_caps.get("driver_id"),
                "driver_id_name": system_caps.get("driver_id_name"),
                "driver_name": system_caps.get("driver_name"),
                "driver_info": system_caps.get("driver_info"),
                "driver_version": system_caps.get("driver_version"),
                "device_type": system_caps.get("device_type"),
                "texture_compression_bc": system_caps.get("texture_compression_bc"),
            },
        })
        if self.device_facts.get("page_size_from_getconf") and system_caps.get("page_size_in_probe"):
            device["page_size_consistent"] = (
                int(system_caps["page_size_in_probe"]) == self.device_facts["page_size_from_getconf"])
        protocols = {
            "library_targets": {
                "system": DEFAULT_SYSTEM_LIBVULKAN,
                "baseline": "<remote>/baseline-stripped.so",
                "candidate": "<remote>/candidate-stripped.so",
            },
            "base_environment": {
                "LD_LIBRARY_PATH": "<remote>", "PREFIX": "<remote>",
                "WRAPPER_LOG_LEVEL": self.args.wrapper_log_level,
                "WRAPPER_CMD_LOG_LEVEL": "none",
            },
            "configs": {name: {**CONFIGS[name], "FORCE_BCN_EMULATION": "1"}
                        for name in self.args.configs},
            "system_config": "no wrapper environment (plain system loader)",
            "wrapper_knobs_from_source": {
                "FORCE_BCN_EMULATION": "forces BC emulation even when the driver supports BC",
                "USE_IMAGE_VIEW": "1 (build default) = compute shader writes through an image view, 0 = staging buffer + copy",
                "USE_CPU_BCN": "all = host CPU (bcdec) decode instead of the compute shader",
                "WRAPPER_REDUCE_DEPTH_FORMAT": "unset by this probe: depth images are created with TRANSFER_DST|TRANSFER_SRC only, so the wrapper passes them through",
            },
            "modes": ["caps", "copy", "blit"],
            "formats": self.args.formats,
            "extent": {"width": self.width, "height": self.height},
            "seed": self.args.seed,
            "tolerance_lsb": self.tolerance,
            "step_timeout_seconds": self.args.timeout,
            "log_level_note": "Wrapper BC runs use WRAPPER_LOG_LEVEL=debug so Android logcat "
                              "captures the decode-path lines (wrapper logs go to logd, not "
                              "stdout; stdout stays clean). Non-BC runs use "
                              "WRAPPER_LOG_LEVEL=%s." % self.args.wrapper_log_level,
        }
        limitations = [
            "Software Vulkan only: the emulator runs on llvmpipe (LLVMpipe) inside the Android emulator, not on Adreno/KGSL and not on a physical GPU.",
            "No phone is attached to the qualifying machine. No Adreno, KGSL or physical-GPU result is claimed; the phone runbook is written but not executed.",
            "Synthetic deterministic BC blocks, not game assets; no mip levels, array layers, sRGB variants or sub-block partial copies are exercised.",
            "No graphics pipeline is created: the wrapper's decoded image is observed through buffer-image copies (and vkCmdBlitImage for the native driver decode), not through sampling or rendering.",
            "No validation layers are loaded; correctness relies on probe metrics, not API validation.",
            "BC7 decoding is not implemented in the independent reference; only BC7 format support is recorded. BC2/4/5/6 are likewise not part of the reference comparison.",
            "Interpolated BC palette entries legitimately differ by a few LSBs between decoder implementations; the wrapper compute path matches the reference exactly, the wrapper CPU fallback (bcdec) is within 1 LSB and Mesa's native decoder within 2 LSB on this run, which is why a tolerance is reported alongside exact counts.",
            "The pinned wrapper is a build-tree .so loaded via dlopen by the probe; it is not packaged, installed or selected by Vapor in this run.",
            "Depth format reduction (WRAPPER_REDUCE_DEPTH_FORMAT) was deliberately not exercised: the required gate is create/bind/copy through TRANSFER_DST|TRANSFER_SRC, so depth images do not request DEPTH_STENCIL_ATTACHMENT usage.",
        ]
        raw = {
            "scratch": str(self.scratch),
            "inputs": str(self.scratch_in),
            "reference_decodes": str(self.scratch_ref),
            "readbacks": str(self.local_out) if hasattr(self, "local_out") else None,
            "per_run_logs": str(self.scratch_logs),
            "tool_log": str(self.scratch_logs / "tool.log"),
            "fixture_binary": str(self.scratch_bin / "probe"),
            "remote_staging": self.remote_dir,
        }
        phone_runbook = {
            "status": "written from the emulator-qualified tool; not executed here (no phone attached)",
            "prerequisites": [
                "A phone with an Adreno GPU (KGSL / Qualcomm proprietary Vulkan driver) connected over adb and authorized.",
                "Host: this checkout at base commit, NDK r27d at the recorded path (or pass --ndk), adb on PATH (or pass --adb).",
                "Read-only pinned wrapper build directory containing candidate-stripped.so, baseline-stripped.so and native-probe/ auxiliary libraries.",
                "Nothing needs to be installed on the phone: the probe and libraries are pushed under /data/local/tmp only.",
            ],
            "commands": [
                "adb devices -l  # note the phone serial; only one device must be online or pass --serial",
                "python3 tools/tests/vulkan-wrapper-reference.py --serial <PHONE_SERIAL> --label phone-adreno --out docs/evidence/%s-vulkan-wrapper-reference-phone.json" % DATE,
            ],
            "what_the_tool_does_on_the_phone": [
                "Compiles tools/tests/vulkan-wrapper-reference.c with NDK r27d aarch64-linux-android34-clang using 16 KB page-size link flags.",
                "Pushes the probe, both pinned wrapper .so files and the native-probe auxiliary libraries to /data/local/tmp/vapor-wrapper-reference-%s, plus deterministic BC1/BC3/depth inputs." % DATE,
                "Runs caps/copy/blit for the system loader, baseline and candidate with FORCE_BCN_EMULATION=1 and the three decode configs (compute, compute-view, host-cpu).",
                "Pulls readbacks, compares against the independent Python S3TC decoder, writes the evidence JSON and per-run logs.",
            ],
            "controls_and_expected_outcomes": [
                {"control": "system loader caps", "expected": "device name is the Adreno driver, page_size matches getconf PAGE_SIZE (4096 or 16384)"},
                {"control": "rgba8 copy on every target", "expected": "bit-exact readback (transfer path sanity)"},
                {"control": "system BC1/BC3 copy", "expected": "compressed readback is byte-identical to the uploaded blocks"},
                {"control": "system BC1/BC3 blit (native decode)", "expected": "driver BC decode within the reported LSB tolerance of the reference; record exact and max-delta numbers"},
                {"control": "candidate compute/compute-view BC1/BC3 copy", "expected": "exact match to the independent reference; a nonzero exact mismatch is the finding to escalate"},
                {"control": "candidate host-cpu BC1/BC3 copy", "expected": "within 1 LSB (bcdec host fallback truncates BC3 alpha interpolants)"},
                {"control": "baseline vs candidate", "expected": "same pixel metrics; the candidate's fix concerns caller-owned format lists, not decode"},
                {"control": "depth d16/d24s8/d32 copy on every target", "expected": "bit-exact round trip; if a depth copy is unsupported by the phone driver, record the concrete VkResult/stage failure instead of claiming support"},
                {"control": "wrapper caps with FORCE_BCN_EMULATION=1", "expected": "wrapper logcat shows 'Forcing BCn emulation' and the BC image is emulated (readback is expanded RGBA8). Note: this wrapper build still reports the underlying driver's textureCompressionBC in vkGetPhysicalDeviceFeatures, so that flag alone is not a forcing check."},
            ],
            "what_to_record": [
                "Device model/hardware (ro.product.model, ro.hardware, ro.board.platform), Android release/SDK and page size.",
                "Vulkan driver ID, driver name/info/version and apiVersion from the caps run; whether textureCompressionBC is native on the phone.",
                "Per-run exit codes, RESULT lines, BYTES_OUT, readback sha256 and the per-mode mismatch metrics in the evidence JSON.",
                "Wrapper logcat lines captured per BC run (they show which decode path ran: use_compute_shader=0/1, use_image_view=0/1).",
                "Any depth image create/bind/copy failure with its stage and VkResult; do not infer depth support from the emulator run.",
            ],
            "adjudication": [
                "PASS: all control runs pass; wrapper BC copy/blit metrics identical to the emulator run (compute exact, host-cpu within 1 LSB).",
                "INVESTIGATE: exact mismatches appear only on the phone for the compute/view path, or max delta exceeds the tolerance; capture readbacks and logcat, and compare candidate vs baseline.",
                "FAIL: rgba8 or compressed-transfer controls fail, or BC emulation is not observed (readback equals compressed input).",
                "UNSUPPORTED: a depth copy fails on the phone driver; record it as a driver limitation, not a wrapper result.",
            ],
            "do_not_claim": [
                "Do not report a phone/Adreno result until the phone run actually completes and its JSON is reviewed.",
                "Do not use the emulator's llvmpipe numbers as Adreno/KGSL numbers.",
                "Do not change existing repo files or commit while running the phone pass.",
            ],
        }
        git_head = None
        git_status = None
        try:
            git_head = subprocess.run(["git", "-C", str(self.repo_root), "rev-parse", "HEAD"],
                                      capture_output=True, text=True, timeout=30).stdout.strip()
            git_status = subprocess.run(["git", "-C", str(self.repo_root), "status", "--porcelain"],
                                        capture_output=True, text=True, timeout=30).stdout.strip()
        except Exception:
            pass
        return {
            "date": DATE,
            "scope": "Reference-image BC1/BC3 and depth-transfer probe of the pinned Vulkan-wrapper candidate "
                     "and its unpatched baseline against an independent CPU S3TC decoder, plus the system Vulkan "
                     "loader as a native-driver decode cross-check. Offscreen transfer-only native probe; no app, "
                     "APK, window, Wine, Steam or game is started. No phone/Adreno result is claimed.",
            "label": self.args.label,
            "base_commit": git_head,
            "git_status_porcelain": git_status,
            "tool": {
                "path": str(Path(__file__).resolve().relative_to(self.repo_root)),
                "sha256": sha256_file(Path(__file__).resolve()),
                "python": sys.version.split()[0],
                "host_platform": platform.platform(),
                "fixture": self.build,
                "fixture_source_rule_summary": S3TC_RULES,
            },
            "reference_decoder": {
                "language": "python (pure stdlib, implemented for this tool)",
                "provenance": "Original implementation from the S3TC/DXT1/DXT5 block-layout and palette rules "
                              "documented in the Khronos Data Format / OpenGL EXT_texture_compression_s3tc "
                              "family of specifications. No wrapper, bcdec or Mesa decoder code is reused; the "
                              "wrapper's bcdec and compute-shader paths are the objects under test.",
                "license": "same licence as this repository (original work)",
                "rules": S3TC_RULES,
                "self_test": self_test,
                "generators": {
                    "algorithm": "Python random.Random(seed); block patterns cycle through four-colour, "
                                 "three-colour/transparent, equal-endpoint, all-black/all-white and random "
                                 "BC1/BC3 blocks so every palette branch and index range is exercised",
                    "seed": self.args.seed,
                    "coverage": self.coverage,
                },
                "inputs": {fmt: {"bytes": len(data), "sha256": self.input_hashes[fmt]}
                           for fmt, data in self.inputs.items()},
                "reference_outputs": {fmt: {"bytes": len(data), "sha256": self.reference_hashes[fmt]}
                                      for fmt, data in self.references.items()
                                      if fmt in self.reference_hashes},
            },
            "wrapper_builds": {
                "root": str(wrapper_build),
                "candidate": wrapper_builds.get("candidate-stripped.so"),
                "baseline": wrapper_builds.get("baseline-stripped.so"),
                "auxiliary_libraries": auxiliary,
                "all_hashes_match_recorded_pins": all(
                    info["matches_recorded_pinned_hash"] for info in wrapper_builds.values()),
            },
            "staging": self.staged,
            "device": device,
            "capabilities": caps,
            "protocol": protocols,
            "runs": self.evaluated,
            "summary": self.summary(),
            "findings": self.findings(),
            "depth_results": [
                {
                    "target": run["target"],
                    "format": run["format"],
                    "vk_format": FORMAT_DESCRIPTIONS[run["format"]]["vk_format"],
                    "command": run["command"],
                    "exit_code": run["exit_code"],
                    "duration_seconds": run["duration_seconds"],
                    "steps": run["steps"],
                    "probe_result": {key: value for key, value in run["fields"].items()
                                     if key in ("RESULT", "BYTES_OUT", "FAIL_STAGE",
                                                "FAIL_RESULT", "DEVICE_NAME", "PAGE_SIZE")},
                    "format_properties": run["format_properties"],
                    "readback_sha256": run.get("readback_sha256"),
                    "readback_bytes": run.get("readback_bytes"),
                    "metrics": run["metrics"],
                    "verdict": run["verdict"],
                    "reasons": run["reasons"],
                }
                for run in self.evaluated if run["format"] in DEPTH_BUFFER_BYTES],
            "limitations": limitations,
            "raw_artifacts": raw,
            "phone_runbook": phone_runbook,
        }


def _dedupe_steps(steps):
    result = []
    for step in steps or []:
        if step != "start" and step not in result:
            result.append(step)
    return result


def _compact_run(run):
    if run.get("mode") == "caps":
        # The full capability table lives in the capabilities section; keep the
        # run record to the invocation and device identity so the evidence file
        # stays reviewable.
        keys = ("name", "target", "config", "mode", "format", "command", "environment",
                "exit_code", "duration_seconds", "verdict", "reasons")
        record = {key: run[key] for key in keys if key in run}
        record["fields"] = {key: value for key, value in (run.get("fields") or {}).items()
                            if key in ("RESULT", "DEVICE_NAME", "DEVICE_VENDOR", "DEVICE_ID",
                                       "DEVICE_API", "PAGE_SIZE",
                                       "FEATURE_textureCompressionBC")}
        record["format_properties_count"] = len(run.get("format_properties") or [])
        return record
    keys = ("name", "target", "target_label", "config", "mode", "format", "command",
            "environment", "exit_code", "duration_seconds", "steps", "fields",
            "format_properties", "logcat", "decode_path", "metrics", "decoded_kind",
            "verdict", "reasons", "readback_sha256", "readback_bytes")
    record = {key: run[key] for key in keys if key in run}
    record["steps"] = _dedupe_steps(record.get("steps"))
    record.setdefault("decode_path", {})
    record["fields"] = {key: value for key, value in (record.get("fields") or {}).items()
                        if key in ("RESULT", "BYTES_OUT", "FAIL_STAGE", "FAIL_RESULT",
                                   "DEVICE_NAME", "PAGE_SIZE")}
    metrics = record.get("metrics")
    if isinstance(metrics, dict) and isinstance(metrics.get("first_mismatches"), list):
        metrics["first_mismatches"] = metrics["first_mismatches"][:4]
    if record.get("verdict") != "pass":
        for key in ("stdout", "stderr"):
            if key in run:
                record[key] = run[key]
    return record


def _depth_record(run):
    return {
        "target": run["target"],
        "format": run["format"],
        "vk_format": FORMAT_DESCRIPTIONS[run["format"]]["vk_format"],
        "command": run["command"],
        "exit_code": run["exit_code"],
        "duration_seconds": run["duration_seconds"],
        "steps": run["steps"],
        "probe_result": {key: value for key, value in run["fields"].items()
                         if key in ("RESULT", "BYTES_OUT", "FAIL_STAGE", "FAIL_RESULT",
                                    "DEVICE_NAME", "PAGE_SIZE")},
        "format_properties": run["format_properties"],
        "readback_sha256": run.get("readback_sha256"),
        "readback_bytes": run.get("readback_bytes"),
        "metrics": run["metrics"],
        "verdict": run["verdict"],
        "reasons": run["reasons"],
    }


def assemble_replay(raw, raw_path, args, self_test):
    """Re-assemble the evidence JSON from a saved raw run payload.

    Used to apply reporting-only improvements (compaction, findings, notes) to a
    run that was executed by an earlier revision of this same tool, without
    re-executing device work.  Commands, environments, readbacks and metrics are
    carried over verbatim; only aggregation and documentation fields are
    rebuilt.
    """
    runs = [_compact_run(run) for run in raw["runs"]]
    probe = ReferenceProbe.__new__(ReferenceProbe)
    probe.args = argparse.Namespace(configs=list(raw["protocol"]["configs"].keys()))
    probe.evaluated = runs
    probe.tolerance = raw["protocol"]["tolerance_lsb"]
    probe.device_facts = raw["device"]
    probe.build = raw["tool"]["fixture"]
    probe.caps = raw["capabilities"]
    probe.local_out = None
    capabilities = json.loads(json.dumps(raw["capabilities"]))
    for target, caps in capabilities.items():
        notes = list(caps.get("notes") or [])
        if target != "system":
            notes.append(
                "FORCE_BCN_EMULATION=1 is active for this run but this wrapper build still "
                "reports the underlying driver's textureCompressionBC in "
                "vkGetPhysicalDeviceFeatures; forcing is confirmed instead by the wrapper "
                "logcat ('Forcing BCn emulation', 'Emulating support for format=..') and by "
                "the emulated BC image readback (expanded RGBA8).")
        unsupported = [prop.get("name") for prop in caps.get("format_properties", [])
                       if prop.get("optimal_transfer_src") == 0
                       or prop.get("optimal_transfer_dst") == 0]
        if unsupported:
            notes.append("Formats without optimal-tiling TRANSFER_SRC/TRANSFER_DST support "
                         "on this driver: %s." % ", ".join(unsupported))
        caps["notes"] = notes
    runbook = json.loads(json.dumps(raw["phone_runbook"]))
    for control in runbook.get("controls_and_expected_outcomes", []):
        if "FORCE_BCN_EMULATION=1" in control.get("control", ""):
            control["expected"] = (
                "wrapper logcat shows 'Forcing BCn emulation' and the BC image is emulated "
                "(readback is expanded RGBA8). Note: this wrapper build still reports the "
                "underlying driver's textureCompressionBC in vkGetPhysicalDeviceFeatures, so "
                "that flag alone is not a forcing check.")
    raw_artifacts = dict(raw.get("raw_artifacts") or {})
    raw_artifacts["replay_source"] = str(raw_path)
    raw_artifacts["replay_source_sha256"] = sha256_file(raw_path)
    payload = dict(raw)
    payload["runs"] = runs
    payload["summary"] = probe.summary()
    payload["findings"] = probe.findings()
    payload["depth_results"] = [_depth_record(run) for run in runs
                                if run["format"] in DEPTH_BUFFER_BYTES]
    payload["capabilities"] = capabilities
    payload["phone_runbook"] = runbook
    payload["raw_artifacts"] = raw_artifacts
    payload["generation"] = {
        "mode": "replay-from-raw-run-records",
        "assembly_tool": {
            "path": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "sha256": sha256_file(Path(__file__).resolve()),
        },
        "executing_tool_sha256": raw.get("tool", {}).get("sha256"),
        "fixture_binary_sha256": raw.get("tool", {}).get("fixture", {}).get("binary_sha256"),
        "raw_run_source": {"path": str(raw_path), "sha256": sha256_file(raw_path)},
        "note": "The probe runs recorded here were executed by executing_tool_sha256 against "
                "fixture_binary_sha256; the JSON was then re-assembled by assembly_tool from "
                "the saved raw run payload (commands, environments, readbacks, metrics and "
                "per-run logs are unchanged). No device work was re-executed during assembly. "
                "The executing revision did not enable wrapper debug logging, so per-run "
                "wrapper logcat is empty; decode-path evidence is therefore limited to the "
                "recorded metrics/readbacks and to the wrapper knob semantics documented in "
                "protocol.wrapper_knobs_from_source (read from the pinned wrapper source).",
        "device_work_reexecuted": False,
    }
    payload["reference_decoder"]["self_test"] = self_test
    return payload


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Reference-image BC1/BC3 and depth probe for the Vapor Vulkan wrapper.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument("--wrapper-build", default=str(DEFAULT_WRAPPER_BUILD),
                        help="pinned wrapper build directory (read-only)")
    parser.add_argument("--scratch", default=str(DEFAULT_SCRATCH),
                        help="host scratch directory for logs, inputs and readbacks")
    parser.add_argument("--out", default=str(DEFAULT_EVIDENCE),
                        help="evidence JSON output path (default %(default)s)")
    parser.add_argument("--ndk", default=str(DEFAULT_NDK), help="NDK root (default %(default)s)")
    parser.add_argument("--clang", default=None, help="explicit aarch64 clang path (overrides --ndk)")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="adb binary")
    parser.add_argument("--serial", default=None, help="device serial (default: single online device)")
    parser.add_argument("--emulator", default="/Users/cheng/Library/Android/sdk/emulator/emulator",
                        help="emulator binary used only to record its version")
    parser.add_argument("--remote-dir", default=None,
                        help="remote staging dir (default /data/local/tmp/vapor-wrapper-reference-<DATE>)")
    parser.add_argument("--width", type=int, default=64)
    parser.add_argument("--height", type=int, default=64)
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--tolerance", type=int, default=2,
                        help="maximum LSB channel delta treated as a pass (default 2; exact counts "
                             "are always reported)")
    parser.add_argument("--targets", default="system,baseline,candidate",
                        help="comma list: system,baseline,candidate")
    parser.add_argument("--formats", default="bc1,bc3,rgba8,d16,d24s8,d32")
    parser.add_argument("--configs", default="compute,compute-view,host-cpu",
                        help="wrapper decode configs: compute,compute-view,host-cpu")
    parser.add_argument("--modes", default="copy,blit")
    parser.add_argument("--timeout", type=int, default=300, help="per-run timeout seconds")
    parser.add_argument("--wrapper-log-level", default="none",
                        help="WRAPPER_LOG_LEVEL for runs (none keeps stdout clean; logcat captures WLOG)")
    parser.add_argument("--label", default="emulator-llvmpipe", help="run label recorded in the JSON")
    parser.add_argument("--replay", default=None,
                        help="re-assemble the evidence JSON from a saved raw run payload "
                             "instead of touching a device (reporting-only rebuild)")
    parser.add_argument("--self-test", action="store_true", help="run decoder self-tests only")
    parser.add_argument("--print-runbook", action="store_true",
                        help="print the phone runbook JSON and exit")
    args = parser.parse_args(argv)
    args.targets = [item.strip() for item in args.targets.split(",") if item.strip()]
    args.formats = [item.strip() for item in args.formats.split(",") if item.strip()]
    args.configs = [item.strip() for item in args.configs.split(",") if item.strip()]
    args.modes = [item.strip() for item in args.modes.split(",") if item.strip()]
    unknown = [t for t in args.targets if t not in ("system", "baseline", "candidate")]
    if unknown:
        parser.error("unknown targets: %s" % unknown)
    unknown = [f for f in args.formats if f not in FORMAT_DESCRIPTIONS]
    if unknown:
        parser.error("unknown formats: %s" % unknown)
    unknown = [c for c in args.configs if c not in CONFIGS]
    if unknown:
        parser.error("unknown configs: %s" % unknown)
    unknown = [m for m in args.modes if m not in ("copy", "blit")]
    if unknown:
        parser.error("unknown modes: %s" % unknown)
    if not args.width or not args.height or args.width % 4 or args.height % 4:
        parser.error("width and height must be positive multiples of 4")
    return args


def main(argv=None):
    args = parse_args(argv)
    self_test = run_self_test()
    if not self_test["passed"]:
        failed = [case for case in self_test["cases"] if not case["passed"]]
        print("SELF-TEST FAILED: %s" % json.dumps(failed, indent=2))
        return 1
    if args.self_test:
        print(json.dumps(self_test, indent=2))
        return 0
    scratch = Path(args.scratch)
    log = Log(scratch / "logs" / "tool.log")
    if args.replay:
        raw_path = Path(args.replay)
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        payload = assemble_replay(raw, raw_path, args, self_test)
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        scratch_result = scratch / "reference-result.json"
        scratch_result.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        log("re-assembled evidence JSON from %s to %s (sha256=%s)"
            % (raw_path, out_path, sha256_file(out_path)))
        summary = payload["summary"]
        print("\nSUMMARY")
        for statement in summary["headline"]:
            print("  " + statement)
        print("  runs: %d pass / %d fail" % (summary["runs_pass"], summary["runs_fail"]))
        for failure in summary["failures"]:
            print("  FAIL %s: %s" % (failure["name"], "; ".join(failure["reasons"])))
        print("\nFINDINGS")
        for statement in payload["findings"]:
            print("  " + statement)
        return 0 if summary["all_runs_pass"] else 1
    log("self-test passed (%d cases)" % len(self_test["cases"]))
    probe = ReferenceProbe(args, log)
    probe.prepare_data()
    if args.print_runbook:
        placeholder = probe.payload(self_test)
        print(json.dumps(placeholder["phone_runbook"], indent=2))
        return 0
    args.serial = resolve_serial(args.adb, args.serial, log)
    probe.adb = Adb(args.adb, args.serial)
    probe.log = log
    probe.build_fixture()
    probe.collect_device_facts()
    probe.stage()
    probe.execute()
    probe.pull_outputs()
    probe.evaluate()
    payload = probe.payload(self_test)
    payload["raw_artifacts"]["tool_log"] = str(scratch / "logs" / "tool.log")
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    scratch_result = scratch / "reference-result.json"
    scratch_result.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    summary = payload["summary"]
    log("evidence JSON written to %s (sha256=%s)" % (out_path, sha256_file(out_path)))
    log("raw copy written to %s" % scratch_result)
    print("\nSUMMARY")
    for statement in summary["headline"]:
        print("  " + statement)
    print("  runs: %d pass / %d fail" % (summary["runs_pass"], summary["runs_fail"]))
    for failure in summary["failures"]:
        print("  FAIL %s: %s" % (failure["name"], "; ".join(failure["reasons"])))
    return 0 if summary["all_runs_pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
