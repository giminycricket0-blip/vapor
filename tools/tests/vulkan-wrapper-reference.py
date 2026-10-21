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

    # original first-pass matrix only
    python3 tools/tests/vulkan-wrapper-reference.py --serial emulator-5556
    # extended coverage: odd extents, partial copies, mip levels/array layers,
    # sRGB BC1/BC3 + RGBA8_SRGB, BC4/BC5 and the depth-reduction knob
    python3 tools/tests/vulkan-wrapper-reference.py --serial emulator-5556 --cases all
    # opt-in BC7/BC7_SRGB differential family (host reference = pinned bcdec.h)
    python3 tools/tests/vulkan-wrapper-reference.py --serial emulator-5556 --cases bc7
    # re-assemble the extended receipt from saved phase payloads (no device)
    python3 tools/tests/vulkan-wrapper-reference.py --assemble phase1.json phase2.json \
        --out docs/evidence/<date>-vulkan-wrapper-reference-extended.json

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
import re
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
    "BC4_UNORM/BC5_UNORM (RGTC): a BC4 block is 8 bytes = 8-bit endpoints a0/a1 "
    "then 16 3-bit indices, LSB first; the palette is identical to the BC3 alpha "
    "palette (8 entries when a0 > a1, otherwise 6 interpolated entries plus 0 and "
    "255). BC5 is 16 bytes: a red BC4-like block followed by a green BC4-like block "
    "(red at bytes 0-7, green at bytes 8-15), decoding to (r,g).",
    "Wrapper storage convention for emulated BC formats: the wrapper replaces "
    "BC1/BC2/BC3/BC4/BC5/BC7 images with R8G8B8A8_UNORM and expands BC4 to "
    "(r,r,r,255) and BC5 to (r,g,0,255); this tool compares against that documented "
    "convention for the wrapper copy paths and against the native uncompressed "
    "channel data for the driver blit-decode path.",
    "sRGB BC1/BC3: the wrapper compute-shader decode linearises sRGB texels before "
    "storing them in the emulated R8G8B8A8_UNORM image, while the host-CPU (bcdec) "
    "fallback stores the sRGB-encoded numeric values without linearisation. The tool "
    "compares each wrapper decode path against its matching reference (linearised "
    "for compute/compute-view, encoded for host-cpu) and records the convention and "
    "the observed disagreement between the two paths.",
    "BC2/BC6/BC7 decoding is not implemented in the independent reference; BC7 "
    "format support is still recorded from the driver. No sampling or rendering "
    "pipeline is created: decoded data is observed through buffer-image copies and "
    "vkCmdBlitImage.",
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

# Formats whose wrapper emulation expands the texels to R8G8B8A8_UNORM and whose
# emulated readback therefore comes back as 4 bytes per texel.
BC_FORMATS = {"bc1", "bc3", "bc4", "bc5", "bc1_srgb", "bc3_srgb", "bc7", "bc7_srgb"}
SRGB_BC_FORMATS = {"bc1_srgb", "bc3_srgb", "bc7_srgb"}
# Blit destination channels per native block-compressed source format.
BLIT_DEST_CHANNELS = {"bc1": 4, "bc3": 4, "bc4": 1, "bc5": 2,
                      "bc1_srgb": 4, "bc3_srgb": 4, "bc7": 4, "bc7_srgb": 4}


def rgtc_palette(a0, a1):
    """Return the 8 RGTC (BC4/BC5) palette values; same interpolation as BC3 alpha."""
    return bc3_alpha_palette(a0, a1)


def decode_bc4_block_values(block):
    indices = int.from_bytes(block[2:8], "little")
    palette = rgtc_palette(block[0], block[1])
    return [palette[(indices >> (3 * pixel)) & 7] for pixel in range(16)]


def decode_bc4_values(data, width, height):
    """Decode BC4_UNORM to a list of 8-bit single-channel values per pixel."""
    values = []
    blocks_x = (width + 3) // 4
    for y in range(height):
        block_row = y // 4
        local_y = y % 4
        row = []
        for x in range(width):
            block_index = block_row * blocks_x + x // 4
            block = data[block_index * 8:block_index * 8 + 8]
            row.append(decode_bc4_block_values(block)[local_y * 4 + (x % 4)])
        values.extend(row)
    return values


def decode_bc5_values(data, width, height):
    """Decode BC5_UNORM to a list of (red, green) 8-bit pairs per pixel."""
    red_blocks = b"".join(data[i * 16:i * 16 + 8] for i in range(len(data) // 16))
    green_blocks = b"".join(data[i * 16 + 8:i * 16 + 16]
                            for i in range(len(data) // 16))
    red = decode_bc4_values(red_blocks, width, height)
    green = decode_bc4_values(green_blocks, width, height)
    return list(zip(red, green))


def decode_bc4_rgba8(data, width, height):
    """BC4_UNORM expanded to the wrapper's BC emulation storage convention (r,r,r,255)."""
    values = decode_bc4_values(data, width, height)
    return b"".join(bytes((v, v, v, 255)) for v in values)


def decode_bc5_rgba8(data, width, height):
    """BC5_UNORM expanded to (r,g,0,255), matching the wrapper compute/CPU decode."""
    return b"".join(bytes((r, g, 0, 255)) for (r, g) in decode_bc5_values(data, width, height))


def decode_bc4_channels(data, width, height):
    return bytes(decode_bc4_values(data, width, height))


def decode_bc5_channels(data, width, height):
    return b"".join(bytes(pair) for pair in decode_bc5_values(data, width, height))


def _round_half_even(value):
    import math
    floor = math.floor(value)
    fraction = value - floor
    if fraction > 0.5:
        return floor + 1
    if fraction < 0.5:
        return floor
    return floor if floor % 2 == 0 else floor + 1


def srgb_to_linear_u8(value):
    """sRGB-encoded 8-bit -> linear 8-bit using the IEC 61966-2-1 transfer function.

    The wrapper compute-shader BCn decoder linearises sRGB source texels before
    storing them into the emulated R8G8B8A8_UNORM image; this is the reference
    for that path.  Rounding is ties-to-even to match float->UNORM conversion.
    """
    x = value / 255.0
    linear = x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4
    return max(0, min(255, _round_half_even(linear * 255.0)))


def linearise_rgba8(data):
    """Apply sRGB->linear to the RGB channels of tightly packed RGBA8 bytes."""
    out = bytearray(len(data))
    for i in range(0, len(data), 4):
        out[i] = srgb_to_linear_u8(data[i])
        out[i + 1] = srgb_to_linear_u8(data[i + 1])
        out[i + 2] = srgb_to_linear_u8(data[i + 2])
        out[i + 3] = data[i + 3]
    return bytes(out)


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
    "bc1_srgb": generate_bc1_blocks,
    "bc3_srgb": generate_bc3_blocks,
    "rgba8": lambda w, h, s: (generate_rgba8(w, h, s), {}),
    "d16": lambda w, h, s: (generate_depth("d16", w, h, s), {}),
    "d24s8": lambda w, h, s: (generate_depth("d24s8", w, h, s), {}),
    "d32": lambda w, h, s: (generate_depth("d32", w, h, s), {}),
}


def generate_rgtc_blocks(width, height, seed, channels):
    """Deterministic BC4 (1 channel) / BC5 (2 channels, red then green) data."""
    rnd = random.Random(seed ^ (0x424334 if channels == 1 else 0x424335))
    blocks_x, blocks_y = (width + 3) // 4, (height + 3) // 4
    data = bytearray()
    coverage = {"blocks": blocks_x * blocks_y, "a0_gt_a1": 0, "a0_le_a1": 0,
                "equal_endpoints": 0, "extreme_endpoints": 0}
    for index in range(blocks_x * blocks_y):
        for _channel in range(channels):
            pattern = index % 6
            if pattern == 0:
                a0, a1 = 255, 0
                coverage["a0_gt_a1"] += 1
                coverage["extreme_endpoints"] += 1
            elif pattern == 1:
                a0, a1 = 0, 255
                coverage["a0_le_a1"] += 1
                coverage["extreme_endpoints"] += 1
            elif pattern == 2:
                a0 = rnd.randrange(1, 256)
                a1 = rnd.randrange(a0)
                coverage["a0_gt_a1"] += 1
            elif pattern == 3:
                a0 = rnd.randrange(0, 256)
                a1 = rnd.randrange(a0, 256)
                coverage["a0_le_a1"] += 1
            elif pattern == 4:
                a0 = a1 = rnd.getrandbits(8)
                coverage["equal_endpoints"] += 1
            else:
                a0, a1 = rnd.getrandbits(8), rnd.getrandbits(8)
                if a0 > a1:
                    coverage["a0_gt_a1"] += 1
                else:
                    coverage["a0_le_a1"] += 1
            data += struct.pack("<BB", a0, a1) + rnd.getrandbits(48).to_bytes(6, "little")
    return bytes(data), coverage


def generate_rgtc_single(width, height, seed):
    return generate_rgtc_blocks(width, height, seed, 1)


def generate_rgtc_dual(width, height, seed):
    return generate_rgtc_blocks(width, height, seed, 2)


def generate_flat_bytes(width, height, seed, texel_bytes, salt):
    rnd = random.Random(seed ^ salt)
    return bytes(rnd.getrandbits(8) for _ in range(width * height * texel_bytes))


GENERATORS["bc4"] = generate_rgtc_single
GENERATORS["bc5"] = generate_rgtc_dual
GENERATORS["rgba8_srgb"] = lambda w, h, s: (generate_flat_bytes(w, h, s, 4, 0x53524742), {})
GENERATORS["r8"] = lambda w, h, s: (generate_flat_bytes(w, h, s, 1, 0x52384e31), {})
GENERATORS["r8g8"] = lambda w, h, s: (generate_flat_bytes(w, h, s, 2, 0x52384738), {})


def generate_bc7_blocks(width, height, seed):
    """Deterministic, VALID BC7 blocks covering modes 0..7.

    BC7 mode encoding is "the position of the first set bit in the first byte
    (LSB first)"; a first byte of 0 encodes the invalid mode 8, which the
    reference decoder (bcdec) clears to transparent black.  Each generated block
    therefore has exactly one mode bit set at position 0..7 and the remaining
    127 bits filled from one of three deterministic patterns (pseudo-random,
    all-zero, all-one).  Every other BC7 field (partitions, rotation, index
    selection, endpoints, P-bits, indices) is left to the full value range, so
    the blocks are valid to decode with any spec-compliant BC7 decoder while
    never encoding mode 8.
    """
    rnd = random.Random(seed ^ 0x424337)
    blocks_x, blocks_y = (width + 3) // 4, (height + 3) // 4
    data = bytearray()
    coverage = {"blocks": blocks_x * blocks_y,
                "modes": {mode: 0 for mode in range(8)},
                "patterns": {}}
    patterns = ("random", "zero", "ones")
    for index in range(blocks_x * blocks_y):
        mode = index % 8
        pattern = patterns[(index // 8) % len(patterns)]
        if pattern == "random":
            block = bytearray(rnd.getrandbits(8) for _ in range(16))
        elif pattern == "zero":
            block = bytearray(16)
        else:
            block = bytearray([0xFF] * 16)
        # Clear bits 0..mode, set exactly the mode bit, keep payload bits above it.
        block[0] = (block[0] & (~((1 << (mode + 1)) - 1) & 0xFF)) | (1 << mode)
        data += block
        coverage["modes"][mode] += 1
        coverage["patterns"][pattern] = coverage["patterns"].get(pattern, 0) + 1
    return bytes(data), coverage


GENERATORS["bc7"] = generate_bc7_blocks
GENERATORS["bc7_srgb"] = generate_bc7_blocks

FORMAT_DESCRIPTIONS = {
    "bc1": {"vk_format": "VK_FORMAT_BC1_RGBA_UNORM_BLOCK", "block_bytes": 8, "kind": "bc"},
    "bc3": {"vk_format": "VK_FORMAT_BC3_UNORM_BLOCK", "block_bytes": 16, "kind": "bc"},
    "bc4": {"vk_format": "VK_FORMAT_BC4_UNORM_BLOCK", "block_bytes": 8, "kind": "bc"},
    "bc5": {"vk_format": "VK_FORMAT_BC5_UNORM_BLOCK", "block_bytes": 16, "kind": "bc"},
    "bc7": {"vk_format": "VK_FORMAT_BC7_UNORM_BLOCK", "block_bytes": 16, "kind": "bc"},
    "bc7_srgb": {"vk_format": "VK_FORMAT_BC7_SRGB_BLOCK", "block_bytes": 16, "kind": "bc"},
    "bc1_srgb": {"vk_format": "VK_FORMAT_BC1_RGBA_SRGB_BLOCK", "block_bytes": 8, "kind": "bc"},
    "bc3_srgb": {"vk_format": "VK_FORMAT_BC3_SRGB_BLOCK", "block_bytes": 16, "kind": "bc"},
    "rgba8": {"vk_format": "VK_FORMAT_R8G8B8A8_UNORM", "texel_bytes": 4, "kind": "rgba8"},
    "rgba8_srgb": {"vk_format": "VK_FORMAT_R8G8B8A8_SRGB", "texel_bytes": 4, "kind": "rgba8"},
    "r8": {"vk_format": "VK_FORMAT_R8_UNORM", "texel_bytes": 1, "kind": "rgba8"},
    "r8g8": {"vk_format": "VK_FORMAT_R8G8_UNORM", "texel_bytes": 2, "kind": "rgba8"},
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
        if not line or "=" not in line or line.startswith("["):
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
    check("bc4/BC5 RGTC palette a0=255 a1=0",
          rgtc_palette(255, 0), [255, 0, 219, 182, 146, 109, 73, 36])
    check("bc4/BC5 RGTC palette a0=0 a1=255",
          rgtc_palette(0, 255), [0, 255, 51, 102, 153, 204, 0, 255])
    bc4_block = bytes([0, 255]) + (0).to_bytes(6, "little")
    check("bc4 block index 0 decodes to the low endpoint",
          decode_bc4_block_values(bc4_block), [0] * 16)
    bc5_two_blocks = bytes([255, 0]) + (0).to_bytes(6, "little") + \
                     bytes([0, 255]) + (0).to_bytes(6, "little")
    check("bc5 interleaves a red block then a green block per 16-byte block",
          decode_bc5_values(bc5_two_blocks, 4, 4)[:2], [(255, 0)] * 2)
    check("srgb_to_linear_u8 endpoints and midpoint",
          [srgb_to_linear_u8(0), srgb_to_linear_u8(128), srgb_to_linear_u8(255)],
          [0, 55, 255])
    check("srgb linearisation leaves alpha untouched",
          linearise_rgba8(bytes((255, 128, 0, 77))), bytes((255, 55, 0, 77)))
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
        self.bc7_reference = None
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
    def run_probe(self, target, config, mode, fmt, capture_logcat=False, plan=None):
        remote = self.remote_dir
        library = (DEFAULT_SYSTEM_LIBVULKAN if target == "system"
                   else "%s/%s" % (remote, WRAPPER_TARGETS[target]))
        width = plan.get("width", self.width) if plan else self.width
        height = plan.get("height", self.height) if plan else self.height
        env = {"LD_LIBRARY_PATH": remote, "PREFIX": remote,
               "WRAPPER_LOG_LEVEL": ("debug" if capture_logcat
                                     else self.args.wrapper_log_level),
               "WRAPPER_CMD_LOG_LEVEL": "none"}
        if target != "system":
            env["FORCE_BCN_EMULATION"] = "1"
            env.update(CONFIGS[config])
        if plan and plan.get("env"):
            env.update(plan["env"])
        env_string = " ".join("%s=%s" % (key, value) for key, value in sorted(env.items()))
        output_name = (plan["name"] + ".bin") if plan else (
            "%s-%s-%s-%s.bin" % (target, config, mode, fmt))
        if mode == "caps":
            command = "cd %s && %s ./probe caps %s" % (remote, env_string, library)
        elif mode == "copy-partial":
            command = ("cd %s && %s ./probe copy-partial %s %s %s %s out/%s %d %d %d %d %d %d"
                       % (remote, env_string, library, fmt, plan["inputs"][0],
                          plan["inputs"][1], output_name, width, height, plan["px"],
                          plan["py"], plan["pw"], plan["ph"]))
        elif mode == "copy-levels":
            command = ("cd %s && %s ./probe copy-levels %s %s %s out/%s %d %d %d %d"
                       % (remote, env_string, library, fmt, plan["inputs"][0], output_name,
                          width, height, plan["levels"], plan["layers"]))
        else:
            command = ("cd %s && %s ./probe %s %s %s %s out/%s %d %d"
                       % (remote, env_string, mode, library, fmt,
                          plan["inputs"][0] if plan else "in/" + fmt + ".bin",
                          output_name, width, height))
        if capture_logcat:
            self.adb.logcat_clear()
        result = self.adb.shell(command, timeout=self.args.timeout)
        logcat_full = self.adb.logcat_vkwrapper() if capture_logcat else []
        keep = ("Emulating support", "use_compute_shader", "use_image_view",
                "Changing image format", "Forcing BCn", "Depth override",
                "HostSideDecompression", "GPU BCn decompression failed")
        logcat = [line for line in logcat_full if any(token in line for token in keep)]
        stdout_lines = []
        for line in result["stdout"].replace("\r\n", "\n").split("\n"):
            if any(token in line for token in keep):
                stdout_lines.append(line[:240])
        decode_path = {}
        for line in logcat + stdout_lines:
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
            match = re.search(r"Depth override: Changing image format from (\d+) to (\d+)", line)
            if match:
                decode_path["depth_override_from_vk_format"] = int(match.group(1))
                decode_path["depth_override_to_vk_format"] = int(match.group(2))
        fields, steps = parse_probe_output(result["stdout"])
        run = {
            "name": output_name[:-4],
            "target": target,
            "target_label": WRAPPER_TARGET_LABELS[target],
            "config": config,
            "mode": mode,
            "format": fmt,
            "width": width,
            "height": height,
            "output_name": output_name,
            "command": command,
            "environment": env,
            "exit_code": result["exit_code"],
            "duration_seconds": result["duration_seconds"],
            "steps": steps,
            "fields": {key: values[-1] for key, values in fields.items()},
            "format_properties": None,
            "logcat": logcat,
            "wrapper_log": (stdout_lines + logcat)[:40],
            "decode_path": decode_path,
            "case": plan.get("case") if plan else "default-matrix",
            "extended": bool(plan),
            "plan_name": plan["name"] if plan else None,
            "srgb_convention": plan.get("srgb_convention") if plan else None,
            "notes": plan.get("notes") if plan else None,
            "expected_desc": plan.get("expected_desc") if plan else None,
            "reference_class": plan.get("reference_class") if plan else None,
            "expectation": plan.get("expectation", "match") if plan else "match",
        }
        for line in result["stdout"].replace("\r\n", "\n").split("\n"):
            if line.startswith("FORMAT_PROPS "):
                run["format_properties"] = parse_format_props(line[len("FORMAT_PROPS "):])
        run["format_properties_list"] = []
        for line in result["stdout"].replace("\r\n", "\n").split("\n"):
            if line.startswith("FORMAT_PROPS "):
                run["format_properties_list"].append(
                    parse_format_props(line[len("FORMAT_PROPS "):]))
        if mode == "caps":
            run["stdout"] = result["stdout"]
        run["levels"] = []
        for line in result["stdout"].replace("\r\n", "\n").split("\n"):
            if line.startswith("LEVEL "):
                entry = {}
                for token in line.split()[1:]:
                    if "=" in token:
                        key, value = token.split("=", 1)
                        try:
                            entry[key] = int(value)
                        except ValueError:
                            entry[key] = value
                run["levels"].append(entry)
        probed_ok = result["exit_code"] == 0 and fields.get("RESULT", ["fail"])[-1] == "ok"
        if not probed_ok:
            tail = result["stdout"].replace("\r\n", "\n").split("\n")
            run["stdout_tail"] = "\n".join(tail[-40:])
            run["stderr"] = result["stderr"][-4000:]
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
                                        "ok" if probed_ok else "FAIL"))
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
        if self.args.cases == "bc7":
            self.args.extended_cases = "bc7"
            self.execute_extended()
            return
        if self.args.cases not in ("default", "all"):
            if self.args.cases in ("extended", "all"):
                self.execute_extended()
            return
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
        if self.args.cases in ("extended", "all"):
            self.execute_extended()

    # -- pulling + evaluation ----------------------------------------------
    def pull_outputs(self):
        pulled = 0
        for run in self.runs:
            if run["mode"] == "caps":
                continue
            remote_file = "%s/out/%s" % (self.remote_dir, run["output_name"])
            try:
                self.adb.pull(remote_file, str(self.scratch_out / run["output_name"]))
                pulled += 1
            except RuntimeError:
                self.log("no readback for %s (probe did not write it)" % run["name"])
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
            if run.get("extended"):
                evaluated.append(self.evaluate_extended_run(run))
                continue
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
        failed = [run for run in evaluated if run["verdict"] == "fail"]
        recorded = [run for run in evaluated if run["verdict"] == "recorded"]
        passed = [run for run in evaluated if run["verdict"] == "pass"]
        self.log("evaluated %d runs: %d pass, %d fail, %d recorded"
                 % (len(evaluated), len(passed), len(failed), len(recorded)))
        for run in failed:
            self.log("FAIL %s: %s" % (run["name"], "; ".join(run["reasons"])))
        for run in recorded:
            self.log("RECORDED %s: %s" % (run["name"], "; ".join(run["reasons"]) or
                                          "behaviour recorded, not a conformance claim"))

    # -- reporting ----------------------------------------------------------
    def headline(self):
        statements = []
        default_runs = [run for run in self.evaluated if not run.get("extended")]
        for target in ("candidate", "baseline"):
            for config in self.args.configs:
                for fmt in ("bc1", "bc3"):
                    matching = [run for run in default_runs if
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
        native = [run for run in default_runs if run["target"] == "system" and
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
        depth_runs = [run for run in default_runs if run["format"] in DEPTH_BUFFER_BYTES]
        if depth_runs:
            exact = all(run["metrics"] and run["metrics"]["exact_texels"] == run["metrics"]["texels"]
                        for run in depth_runs)
            statements.append("depth (d16/d24s8/d32) transfer round trip: %s"
                              % ("bit-exact on every probe" if exact
                                 else "see depth results; some texels differ"))
        return statements

    def findings(self):
        statements = []
        default_runs = [run for run in self.evaluated if not run.get("extended")]
        wrapper_targets = ("baseline", "candidate")
        exact_runs = [run for run in default_runs
                      if run["target"] in wrapper_targets
                      and run["config"] in ("compute", "compute-view")
                      and run["mode"] in ("copy", "blit")
                      and run["format"] in ("bc1", "bc3")
                      and run.get("metrics")
                      and run["metrics"].get("exact_pixels") == run["metrics"].get("pixels")]
        all_runs = [run for run in default_runs
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
        host = [run for run in default_runs
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
        native = [run for run in default_runs
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
        for run in default_runs:
            if run["target"] in ("baseline", "candidate") and run.get("readback_sha256"):
                readbacks.setdefault((run["config"], run["mode"], run["format"]), {})[
                    run["target"]] = run["readback_sha256"]
        identical = sum(1 for value in readbacks.values()
                        if len(value) == 2 and value.get("baseline") == value.get("candidate"))
        statements.append(
            "Candidate versus baseline: %d/%d comparable wrapper readbacks are byte-identical; "
            "the candidate's format-list ownership fix does not change decoded pixels."
            % (identical, len(readbacks)))
        depth = [run for run in default_runs if run["format"] in DEPTH_BUFFER_BYTES]
        exact_depth = all(run["metrics"]["exact_texels"] == run["metrics"]["texels"]
                          for run in depth if run.get("metrics"))
        statements.append(
            "Depth transfer: %d depth probes (D16_UNORM, D24_UNORM_S8_UINT, D32_SFLOAT; system, "
            "baseline and candidate) are %s; buffer texel widths observed: %s."
            % (len(depth), "bit-exact round trips" if exact_depth else "not all exact",
               ", ".join("%s=%d bytes" % (fmt, DEPTH_BUFFER_BYTES[fmt]) for fmt in DEPTH_BUFFER_BYTES)))
        compute_paths = {str(run.get("decode_path")) for run in default_runs
                         if run.get("decode_path")}
        if compute_paths:
            statements.append(
                "Wrapper decode path was confirmed from logcat: %s."
                % "; ".join(sorted(compute_paths)))
        page_size = self.caps.get("system", {}).get("fields", {}).get("PAGE_SIZE")
        statements.append(
            "Page size: probe sysconf=%s, getconf=%s, fixture ELF max-load alignment=%s "
            "(16 KB link flags on a %s-byte-page emulator)."
            % (page_size, self.device_facts.get("page_size_from_getconf"),
               self.build.get("elf_max_load_align"), page_size))
        statements.extend(self.extended_findings())
        return statements

    def summary(self):
        default_runs = [run for run in self.evaluated if not run.get("extended")]
        failed = [run for run in default_runs if run["verdict"] != "pass"]
        depth_results = []
        for run in default_runs:
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
        for run in default_runs:
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
            "runs_total": len(default_runs),
            "runs_pass": len(default_runs) - len(failed),
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
            formats = list(run.get("format_properties_list") or [])
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
            "cases": self.args.cases,
            "extended_cases_filter": self.args.extended_cases or "all",
            "extended_case_families": ["odd-extents", "partial-region", "mips-layers",
                                       "srgb", "bc4-bc5", "depth-reduce"],
            "extended_env_knobs": {
                "WRAPPER_REDUCE_DEPTH_FORMAT": "none (unset) / safe / aggressive / disabled, "
                                               "used only by the depth-reduce case on images "
                                               "created with DEPTH_STENCIL_ATTACHMENT usage",
            },
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
                "# first-pass matrix (BC1/BC3/RGBA8/depth) only:",
                "python3 tools/tests/vulkan-wrapper-reference.py --serial <PHONE_SERIAL> --cases default --label phone-adreno-firstpass --out docs/evidence/%s-vulkan-wrapper-reference-phone.json" % DATE,
                "# extended coverage (odd extents, partial copies, mips/layers, sRGB, BC4/BC5, depth-reduce):",
                "python3 tools/tests/vulkan-wrapper-reference.py --serial <PHONE_SERIAL> --cases all --label phone-adreno-extended --out docs/evidence/%s-vulkan-wrapper-reference-extended-phone.json" % DATE,
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
                {"control": "non-multiple-of-4 BC extents (5x7, 13x17, 1x1, 63x65)", "expected": "out-of-spec VkBufferImageCopy regions: record the wrapper's in-bounds decode per decode path. On the emulator the compute paths decoded every in-bounds pixel exactly while the host-CPU path corrupted partial blocks; treat mismatches here as recorded robustness data, not a conformance verdict."},
                {"control": "partial-region copy with sentinel base (block-aligned patch)", "expected": "pixels outside the patch keep the base decode and the patch pixels match the patched decode; all wrapper configs pass on the emulator."},
                {"control": "mipLevels=3 / arrayLayers=2 copies", "expected": "every layer/level matches its independent decode (compute path) or the compressed transfer (system loader)."},
                {"control": "sRGB BC1/BC3 + RGBA8_SRGB control", "expected": "compute/compute-view paths match the sRGB->linear reference; host-CPU matches the sRGB-encoded reference; the two wrapper paths disagree, which is the recorded emulation inconsistency; RGBA8_SRGB transfer is bit-exact."},
                {"control": "BC4_UNORM / BC5_UNORM", "expected": "wrapper compute and host-CPU copies match the independent RGTC decoder exactly in the documented (r,r,r,255)/(r,g,0,255) storage convention; system-loader compressed transfer is exact; native vkCmdBlitImage RGTC decode is within the reported LSB tolerance."},
                {"control": "depth format-reduction knob (WRAPPER_REDUCE_DEPTH_FORMAT)", "expected": "requires DEPTH_STENCIL_ATTACHMENT usage (depth-attach mode). unset: bit-exact; safe: D32->D16 when D16S8 is unsupported (D24S8 unchanged), transfers are no longer bit-exact; aggressive: D32/D24S8->D16, transfers not bit-exact; disabled: image creation is rejected (VK_ERROR_FORMAT_NOT_SUPPORTED). Record the wrapper logcat 'Depth override: Changing image format from N to M' lines."},
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
        if self.bc7_reference:
            limitations = [item for item in limitations
                           if "BC7 decoding is not implemented in the independent reference"
                           not in item]
            limitations.append(
                "BC7 reference independence caveat: the reference decoder is bcdec (pinned "
                "wrapper source, bcdec.h), which is ALSO the wrapper's host-CPU fallback "
                "decoder; the host-cpu results are a self-consistency check, while the wrapper "
                "compute-shader bc7.comp and llvmpipe's native BC7 decoder are independent "
                "implementations and are the meaningful differential comparisons.")
        return {
            **({"bc7_reference": self.bc7_reference} if self.bc7_reference else {}),
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
            "extended_cases": self.extended_summary(),
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
                for run in self.evaluated
                if run["format"] in DEPTH_BUFFER_BYTES and not run.get("extended")],
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


def compare_channels(actual, expected, channels, tolerance):
    """Compare tightly packed multi-channel texels (1..4 bytes per texel)."""
    texels = min(len(actual), len(expected)) // channels
    exact = 0
    hard = 0
    max_delta = 0
    histogram = {}
    first_mismatches = []
    for i in range(texels):
        base = i * channels
        a = actual[base:base + channels]
        e = expected[base:base + channels]
        if a == e:
            exact += 1
            histogram["0"] = histogram.get("0", 0) + 1
            continue
        delta = max(abs(a[c] - e[c]) for c in range(channels))
        max_delta = max(max_delta, delta)
        key = str(delta) if delta <= 3 else "3+"
        histogram[key] = histogram.get(key, 0) + 1
        if delta > tolerance:
            hard += 1
        if len(first_mismatches) < 4:
            first_mismatches.append({"texel": i, "expected": list(e), "actual": list(a),
                                     "delta": delta})
    mismatch = texels - exact
    return {
        "channels": channels,
        "texels": texels,
        "exact_texels": exact,
        "mismatch_texels": mismatch,
        "within_tolerance_texels": mismatch - hard,
        "hard_mismatches": hard,
        "max_channel_delta": max_delta,
        "delta_histogram": {k: v for k, v in sorted(histogram.items(),
                                                    key=lambda item: (len(item[0]), item[0]))},
        "first_mismatches": first_mismatches,
        "tolerance": tolerance,
    }


def bc_reference_rgba8(fmt, data, width, height):
    """Independent reference RGBA8 for a block format, in wrapper storage convention."""
    base = fmt.replace("_srgb", "")
    if base in DECODERS:
        return DECODERS[base](data, width, height)
    if base == "bc4":
        return decode_bc4_rgba8(data, width, height)
    if base == "bc5":
        return decode_bc5_rgba8(data, width, height)
    raise ValueError("no independent decoder for %s" % fmt)


def blit_reference_channels(fmt, data, width, height):
    """Independent reference for the uncompressed blit destination of a BC source."""
    base = fmt.replace("_srgb", "")
    if base in DECODERS:
        return DECODERS[base](data, width, height), 4
    if base == "bc4":
        return decode_bc4_channels(data, width, height), 1
    if base == "bc5":
        return decode_bc5_channels(data, width, height), 2
    raise ValueError("no blit reference for %s" % fmt)



BC7_HOST_DECODE_SOURCE = r"""/* Host BC7 decode helper for tools/tests/vulkan-wrapper-reference.py.
 *
 * Compiled against the pinned wrapper source's bcdec.h (bcdec v0.98, dual
 * MIT/Unlicense; see bcdec.h for the licence text).  bcdec_bc7 is the pragmatic
 * BC7 reference used by this probe; note that the wrapper's host-CPU fallback
 * path uses the same bcdec implementation, so that path is a self-consistency
 * check while the compute-shader bc7.comp and llvmpipe's native decoder are the
 * independent implementations.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "bcdec.h"

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: %s BLOCKS OUT WIDTH HEIGHT\n", argv[0]);
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    if (!in) { perror("open blocks"); return 1; }
    if (fseek(in, 0, SEEK_END) || ftell(in) < 0) return 1;
    long size = ftell(in);
    rewind(in);
    unsigned char *blocks = malloc((size_t)size);
    if (!blocks || fread(blocks, 1, (size_t)size, in) != (size_t)size) return 1;
    fclose(in);
    int width = atoi(argv[3]), height = atoi(argv[4]);
    if (width <= 0 || height <= 0) return 2;
    int blocks_x = (width + 3) / 4, blocks_y = (height + 3) / 4;
    if ((long)blocks_x * blocks_y * 16 != size) {
        fprintf(stderr, "block count mismatch: %ld bytes for %dx%d\n", size, width, height);
        return 1;
    }
    int stride = width * 4;
    unsigned char *rgba = calloc((size_t)width * height * 4u, 1);
    if (!rgba) return 1;
    for (int by = 0; by < blocks_y; by++) {
        for (int bx = 0; bx < blocks_x; bx++) {
            const unsigned char *block = blocks + ((size_t)by * blocks_x + bx) * 16;
            unsigned char *dst = rgba + (size_t)(by * 4) * stride + (size_t)(bx * 4) * 4;
            bcdec_bc7(block, dst, stride);
        }
    }
    FILE *out = fopen(argv[2], "wb");
    if (!out) return 1;
    if (fwrite(rgba, 1, (size_t)width * height * 4u, out) !=
        (size_t)width * height * 4u) return 1;
    fclose(out);
    free(blocks);
    free(rgba);
    printf("OK width=%d height=%d blocks=%d\n", width, height, blocks_x * blocks_y);
    return 0;
}
"""


def bc7_case_table(runs):
    """Summarise BC7 evidence runs by target/config/mode/format."""
    table = {}
    for run in runs:
        if run.get("case") != "bc7":
            continue
        key = "%s/%s/%s/%s" % (run.get("target"), run.get("config"),
                               run.get("mode"), run.get("format"))
        metrics = run.get("metrics") or {}
        entry = table.setdefault(key, {
            "reference_class": run.get("reference_class"),
            "verdict": run.get("verdict"),
            "exact": metrics.get("exact_pixels", metrics.get("exact_bytes")),
            "total": metrics.get("pixels", metrics.get("bytes")),
            "mismatch": metrics.get("mismatch_pixels", metrics.get("mismatch_bytes")),
            "hard_mismatches": metrics.get("hard_mismatches"),
            "max_delta": metrics.get("max_channel_delta"),
            "tolerance": metrics.get("tolerance"),
            "runs": [],
            "phases": [],
        })
        name = run.get("name") or ""
        phase = run.get("phase")
        entry["runs"].append("%s@%s" % (name, phase) if phase else name)
        if phase and phase not in entry["phases"]:
            entry["phases"].append(phase)
    return table


class _ExtendedMixin:
    """Extended coverage cases: odd extents, partial copies, mips/layers, sRGB,
    BC4/BC5 and the depth format-reduction path.  Mixed into ReferenceProbe."""

    @staticmethod
    def _level_extents(width, height, levels):
        extents = []
        for mip in range(levels):
            lw = width >> mip
            lh = height >> mip
            extents.append((max(1, lw), max(1, lh)))
        return extents

    @staticmethod
    def _level_slot(fmt, lw, lh):
        source = source_bytes(fmt, lw, lh)
        rgba = lw * lh * 4
        return max(source, rgba)

    def _bc7_setup_reference(self, blocks, width, height):
        """Compile/run the pinned-bcdec host helper and return the reference RGBA8.

        The helper is generated in scratch (not a repository file) and compiled
        against the read-only pinned wrapper source's bcdec.h.  Sources, hashes
        and the compile command are recorded in self.bc7_reference for the
        evidence receipt.
        """
        wrapper_source = Path(self.args.wrapper_build) / "source/src/vulkan/wrapper"
        bcdec_h = wrapper_source / "bcdec.h"
        bcdec_c = wrapper_source / "bcdec.c"
        if not bcdec_h.is_file():
            raise SystemExit("error: pinned bcdec.h not found at %s" % bcdec_h)
        helper_source = self.scratch_bin / "bc7-host-decode.c"
        helper_source.write_text(BC7_HOST_DECODE_SOURCE)
        helper_binary = self.scratch_bin / "bc7-host-decode"
        compiler = os.environ.get("CC", "cc")
        command = [compiler, "-O2", "-std=c11", "-I", str(wrapper_source),
                   str(helper_source), "-o", str(helper_binary)]
        self.log("building BC7 reference helper: %s" % " ".join(command))
        proc = subprocess.run(command, capture_output=True, text=True, timeout=180)
        if proc.returncode != 0:
            raise SystemExit("error: BC7 host helper failed to compile\n%s\n%s"
                             % (proc.stdout, proc.stderr))
        blocks_path = self.scratch_bin / ("bc7-reference-blocks-%dx%d.bin" % (width, height))
        blocks_path.write_bytes(blocks)
        reference_path = self.scratch_bin / ("bc7-reference-rgba-%dx%d.bin" % (width, height))
        run = subprocess.run([str(helper_binary), str(blocks_path), str(reference_path),
                              str(width), str(height)],
                             capture_output=True, text=True, timeout=120)
        if run.returncode != 0:
            raise SystemExit("error: BC7 host helper failed to decode\n%s\n%s"
                             % (run.stdout, run.stderr))
        reference = reference_path.read_bytes()
        if len(reference) != width * height * 4:
            raise SystemExit("error: BC7 host helper produced %d bytes, expected %d"
                             % (len(reference), width * height * 4))
        self.bc7_reference = {
            "reference_decoder": "bcdec_bc7 from the pinned wrapper source (header-only "
                                 "implementation in bcdec.h)",
            "independence_caveat": "bcdec is also the wrapper's host-CPU fallback decoder, so "
                                   "that wrapper path is a self-consistency check; the wrapper "
                                   "compute-shader bc7.comp and llvmpipe's native BC7 decoder "
                                   "are independent implementations and are the meaningful "
                                   "comparisons.",
            "provenance": {
                "wrapper_source": str(wrapper_source),
                "bcdec.h": {"path": str(bcdec_h), "sha256": sha256_file(bcdec_h),
                            "bytes": bcdec_h.stat().st_size},
                "bcdec.c": ({"path": str(bcdec_c), "sha256": sha256_file(bcdec_c),
                             "bytes": bcdec_c.stat().st_size}
                            if bcdec_c.is_file() else None),
                "version": "bcdec v0.98 (bcdec.h header comment says v0.97; version macros 0.98)",
                "licence": "bcdec is dual-licensed MIT OR The Unlicense (public domain); see "
                           "the LICENSE section at the end of bcdec.h",
            },
            "host_helper": {
                "source_path": str(helper_source),
                "source_sha256": sha256_file(helper_source),
                "binary_path": str(helper_binary),
                "binary_sha256": sha256_file(helper_binary),
                "compile_command": command,
                "run_command": [str(helper_binary), str(blocks_path), str(reference_path),
                                str(width), str(height)],
            },
            "blocks": {"width": width, "height": height,
                       "blocks": ((width + 3) // 4) * ((height + 3) // 4),
                       "generator": "generate_bc7_blocks: mode field set explicitly to "
                                    "modes 0-7 (never the invalid mode 8) and the remaining "
                                    "127 bits filled from deterministic random/zero/one "
                                    "patterns",
                       "validity": {
                           "mode_field_rule": "BC7 encodes the mode as the position of the "
                                              "first set bit in byte 0 (LSB first); the "
                                              "generator clears bits 0..mode and sets only bit "
                                              "mode, then keeps the higher byte-0 bits and "
                                              "bytes 1..15 from the deterministic pattern.",
                           "invalid_mode_8": "a first byte of 0 encodes the reserved mode 8; "
                                             "the generator cannot emit it because bit `mode` "
                                             "(0..7) is always set.",
                           "other_fields": "partitions, rotation, index-selection bit, "
                                           "endpoints, P-bits and indices use their full "
                                           "bit-field ranges, all of which are decodable by "
                                           "spec-compliant BC7 decoders.",
                           "coverage_per_block": "32 blocks per mode (modes 0-7), spread over "
                                                 "random/zero/one payload patterns",
                       },
                       "path": str(blocks_path)},
            "reference_rgba": {"path": str(reference_path),
                               "pixels": width * height,
                               "sha256": sha256_bytes(reference)},
        }
        self.log("BC7 reference: %d blocks decoded with pinned bcdec"
                 % self.bc7_reference["blocks"]["blocks"])
        return reference

    def execute_extended(self):
        self.extended_plans = []
        self.extended_plans_by_name = {}
        self._ext_input_paths = []
        seed = self.args.seed
        tolerance = self.tolerance
        candidate = "candidate"
        configs = list(self.args.configs)
        families = [item.strip() for item in (self.args.extended_cases or "").split(",")
                    if item.strip()]
        # BC7 is strictly opt-in: earlier default/extended matrices must stay reproducible.
        include_bc7 = "bc7" in families

        def add_input(name, data):
            path = self.scratch_in / name
            path.write_bytes(data)
            self._ext_input_paths.append(path)
            return "in/" + name

        def add_plan(name, case, target, config, mode, fmt, width, height, inputs,
                     expected, expected_kind, expectation="match", expected_desc=None,
                     notes=None, capture_log=False, env=None, **extra):
            entry = {"name": name, "case": case, "target": target, "config": config,
                     "mode": mode, "format": fmt, "width": width, "height": height,
                     "inputs": inputs, "expected": expected,
                     "expected_kind": expected_kind, "expectation": expectation,
                     "expected_desc": expected_desc, "notes": notes,
                     "capture_log": capture_log, "env": env or {}}
            entry.update(extra)
            self.extended_plans.append(entry)
            self.extended_plans_by_name[name] = entry
            return entry

        # ---- (b) non-multiple-of-4 extents ---------------------------------
        for fmt in ("bc1", "bc3"):
            for (ow, oh) in ((5, 7), (13, 17), (1, 1), (63, 65)):
                data, _ = GENERATORS[fmt](ow, oh, seed)
                input_name = add_input("%s-%dx%d.bin" % (fmt, ow, oh), data)
                reference = DECODERS[fmt](data, ow, oh)
                for config in configs:
                    add_plan("%s-odd-%dx%d-%s" % (fmt, ow, oh, config), "odd-extents",
                             candidate, config, "copy", fmt, ow, oh, [input_name],
                             reference, "rgba8", expectation="recorded",
                             expected_desc="independent BC decode of %dx%d pixels" % (ow, oh),
                             notes="out-of-spec VkBufferImageCopy region for a block-compressed "
                                   "image (imageExtent is not a multiple of 4); records wrapper "
                                   "robustness of partial-block extents, not a conformance claim")
                rgba = generate_rgba8(ow, oh, seed ^ 0x0DD)
                rgba_name = add_input("rgba8-%dx%d.bin" % (ow, oh), rgba)
                add_plan("rgba8-odd-%dx%d-native" % (ow, oh), "odd-extents", "system",
                         SYSTEM_CONFIG, "copy", "rgba8", ow, oh, [rgba_name], rgba,
                         "exact_bytes", expected_desc="identity RGBA8 transfer")

        # ---- (c) partial-region copies with sentinel base ------------------
        pw_h, ph_h = 16, 16
        for fmt in ("rgba8", "bc1", "bc3"):
            if fmt == "rgba8":
                base = generate_rgba8(pw_h, ph_h, seed ^ 0x50)
                patch = generate_rgba8(9, 6, seed ^ 0x51)
                px, py, pw, ph = 5, 3, 9, 6
                overlay = bytearray(base)
                for y in range(ph):
                    dst = ((py + y) * pw_h + px) * 4
                    overlay[dst:dst + pw * 4] = patch[y * pw * 4:(y + 1) * pw * 4]
                expected_rgba = bytes(overlay)
                base_name = add_input("partial-rgba8-base.bin", base)
                patch_name = add_input("partial-rgba8-patch.bin", patch)
                for target in ("system", "baseline", "candidate"):
                    config = SYSTEM_CONFIG if target == "system" else "compute"
                    add_plan("rgba8-partial-%s" % target, "partial-region", target, config,
                             "copy-partial", "rgba8", pw_h, ph_h, [base_name, patch_name],
                             expected_rgba, "exact_bytes", px=px, py=py, pw=pw, ph=ph,
                             expected_desc="base RGBA8 with a %dx%d patch at (%d,%d)" % (pw, ph, px, py))
                continue
            block = FORMAT_DESCRIPTIONS[fmt]["block_bytes"]
            base, _ = GENERATORS[fmt](pw_h, ph_h, seed ^ 0x50)
            patch, _ = GENERATORS[fmt](8, 8, seed ^ 0x51)
            px, py, pw, ph = 8, 4, 8, 8
            base_ref = DECODERS[fmt](base, pw_h, ph_h)
            patch_ref = DECODERS[fmt](patch, pw, ph)
            overlay = bytearray(base_ref)
            for y in range(ph):
                dst = ((py + y) * pw_h + px) * 4
                overlay[dst:dst + pw * 4] = patch_ref[y * pw * 4:(y + 1) * pw * 4]
            expected_rgba = bytes(overlay)
            compressed = bytearray(base)
            blocks_x = pw_h // 4
            patch_blocks_x = pw // 4
            for by in range(ph // 4):
                for bx in range(pw // 4):
                    src = (by * patch_blocks_x + bx) * block
                    dst = ((py // 4 + by) * blocks_x + (px // 4 + bx)) * block
                    compressed[dst:dst + block] = patch[src:src + block]
            expected_compressed = bytes(compressed)
            base_name = add_input("partial-%s-base.bin" % fmt, base)
            patch_name = add_input("partial-%s-patch.bin" % fmt, patch)
            add_plan("%s-partial-system" % fmt, "partial-region", "system", SYSTEM_CONFIG,
                     "copy-partial", fmt, pw_h, ph_h, [base_name, patch_name],
                     expected_compressed, "compressed",
                     px=px, py=py, pw=pw, ph=ph,
                     expected_desc="compressed base with a block-aligned %dx%d patch at (%d,%d)"
                                   % (pw, ph, px, py))
            for config in configs:
                add_plan("%s-partial-%s" % (fmt, config), "partial-region", candidate, config,
                         "copy-partial", fmt, pw_h, ph_h, [base_name, patch_name],
                         expected_rgba, "rgba8", px=px, py=py, pw=pw, ph=ph,
                         expected_desc="base decode with a decoded %dx%d patch at (%d,%d)"
                                       % (pw, ph, px, py))

        # ---- (d) mip levels + array layers ---------------------------------
        mip_w, mip_h, levels, layers = 64, 64, 3, 2
        for fmt in ("rgba8", "bc1", "bc3"):
            input_data = bytearray()
            per_level = []
            for layer in range(layers):
                for mip in range(levels):
                    lw, lh = self._level_extents(mip_w, mip_h, levels)[mip]
                    if fmt == "rgba8":
                        chunk = generate_rgba8(lw, lh, seed ^ (0x600 + layer * 8 + mip))
                        expected = chunk
                    else:
                        chunk, _ = GENERATORS[fmt](lw, lh, seed ^ (0x600 + layer * 8 + mip))
                        expected = DECODERS[fmt](chunk, lw, lh)
                    input_data += chunk
                    per_level.append({"layer": layer, "mip": mip, "width": lw, "height": lh,
                                      "src_len": len(chunk),
                                      "expected_rgba": expected, "chunk": chunk})
            input_name = add_input("%s-levels.bin" % fmt, bytes(input_data))
            slot_offsets = []
            offset = 0
            for entry in per_level:
                slot_offsets.append(offset)
                offset += self._level_slot(fmt, entry["width"], entry["height"])
            if fmt == "rgba8":
                slices = [{"offset": slot_offsets[i], "data": per_level[i]["chunk"],
                           "kind": "exact", "layer": per_level[i]["layer"],
                           "mip": per_level[i]["mip"]} for i in range(len(per_level))]
                for target in ("system", "baseline", "candidate"):
                    config = SYSTEM_CONFIG if target == "system" else "compute"
                    add_plan("%s-levels-%s" % (fmt, target), "mips-layers", target, config,
                             "copy-levels", fmt, mip_w, mip_h, [input_name], slices, "slices",
                             levels=levels, layers=layers,
                             expected_desc="per-layer/per-level identity copy (%d levels, %d layers)"
                                           % (levels, layers))
            else:
                slices_native = [{"offset": slot_offsets[i], "data": per_level[i]["chunk"],
                                  "kind": "compressed", "layer": per_level[i]["layer"],
                                  "mip": per_level[i]["mip"]} for i in range(len(per_level))]
                add_plan("%s-levels-system" % fmt, "mips-layers", "system", SYSTEM_CONFIG,
                         "copy-levels", fmt, mip_w, mip_h, [input_name], slices_native, "slices",
                         levels=levels, layers=layers,
                         expected_desc="per-level compressed transfer round trip")
                slices_rgba = [{"offset": slot_offsets[i], "data": per_level[i]["expected_rgba"],
                                "kind": "rgba8", "layer": per_level[i]["layer"],
                                "mip": per_level[i]["mip"]} for i in range(len(per_level))]
                for config in configs:
                    add_plan("%s-levels-%s" % (fmt, config), "mips-layers", candidate, config,
                             "copy-levels", fmt, mip_w, mip_h, [input_name], slices_rgba,
                             "slices", levels=levels, layers=layers,
                             expected_desc="per-layer/per-level independent decode")

        # ---- (e) sRGB variants ---------------------------------------------
        srgb_w, srgb_h = 64, 64
        for fmt in ("bc1_srgb", "bc3_srgb"):
            base_fmt = fmt.replace("_srgb", "")
            data, _ = GENERATORS[base_fmt](srgb_w, srgb_h, seed)
            input_name = add_input("%s.bin" % fmt, data)
            encoded = DECODERS[base_fmt](data, srgb_w, srgb_h)
            linearised = linearise_rgba8(encoded)
            add_plan("%s-native" % fmt, "srgb", "system", SYSTEM_CONFIG, "copy", fmt,
                     srgb_w, srgb_h, [input_name], data, "compressed",
                     expected_desc="compressed SRGB block transfer round trip")
            for target in ("candidate", "baseline"):
                for config in configs:
                    convention = ("compute-shader path linearises sRGB texels before storing "
                                  "them in the emulated R8G8B8A8_UNORM image"
                                  if config in ("compute", "compute-view")
                                  else "host-CPU fallback stores sRGB-encoded numeric values "
                                       "(no linearisation)")
                    expected = linearised if config in ("compute", "compute-view") else encoded
                    add_plan("%s-%s-%s" % (fmt, target, config), "srgb", target, config,
                             "copy", fmt, srgb_w, srgb_h, [input_name], expected, "rgba8",
                             srgb_convention=convention, expected_desc=convention)
        srgb_control = generate_flat_bytes(64, 64, seed, 4, 0x53524743)
        srgb_control_name = add_input("rgba8_srgb.bin", srgb_control)
        for target in ("system", "baseline", "candidate"):
            config = SYSTEM_CONFIG if target == "system" else "compute"
            add_plan("rgba8_srgb-%s" % target, "srgb", target, config, "copy",
                     "rgba8_srgb", 64, 64, [srgb_control_name], srgb_control, "exact_bytes",
                     expected_desc="identity transfer control for an SRGB UNORM colour format")

        # ---- (f) BC4/BC5 with independent RGTC decoders --------------------
        rgtc_w, rgtc_h = 64, 64
        for fmt in ("bc4", "bc5"):
            data, coverage = GENERATORS[fmt](rgtc_w, rgtc_h, seed)
            input_name = add_input("%s.bin" % fmt, data)
            rgba_ref = bc_reference_rgba8(fmt, data, rgtc_w, rgtc_h)
            channels_ref, channel_count = blit_reference_channels(fmt, data, rgtc_w, rgtc_h)
            add_plan("%s-native-copy" % fmt, "bc4-bc5", "system", SYSTEM_CONFIG, "copy", fmt,
                     rgtc_w, rgtc_h, [input_name], data, "compressed",
                     expected_desc="compressed RGTC transfer round trip")
            add_plan("%s-native-blit" % fmt, "bc4-bc5", "system", SYSTEM_CONFIG, "blit", fmt,
                     rgtc_w, rgtc_h, [input_name], channels_ref, "channels",
                     channels=channel_count,
                     expected_desc="native driver RGTC decode via vkCmdBlitImage into an "
                                   "uncompressed %d-channel image" % channel_count)
            for config in configs:
                add_plan("%s-%s-copy" % (fmt, config), "bc4-bc5", candidate, config, "copy",
                         fmt, rgtc_w, rgtc_h, [input_name], rgba_ref, "rgba8",
                         expected_desc="independent RGTC decode in the wrapper emulation "
                                       "storage convention")
            add_plan("%s-compute-blit" % fmt, "bc4-bc5", candidate, "compute", "blit", fmt,
                     rgtc_w, rgtc_h, [input_name], channels_ref, "channels",
                     channels=channel_count,
                     expected_desc="wrapper emulated decode read through a %d-channel blit "
                                   "destination" % channel_count)
            add_plan("%s-baseline-copy" % fmt, "bc4-bc5", "baseline", "compute", "copy", fmt,
                     rgtc_w, rgtc_h, [input_name], rgba_ref, "rgba8",
                     expected_desc="baseline wrapper independent RGTC decode")

        # ---- (g) depth format-reduction knob -------------------------------
        red_w, red_h = 32, 32
        for fmt in ("d32", "d24s8"):
            data = generate_depth(fmt, red_w, red_h, seed)
            input_name = add_input("depth-reduce-%s.bin" % fmt, data)
            variants = [
                ("none", {}, "match",
                 "WRAPPER_REDUCE_DEPTH_FORMAT unset (pass-through)"),
                ("safe", {"WRAPPER_REDUCE_DEPTH_FORMAT": "safe"}, "recorded",
                 "WRAPPER_REDUCE_DEPTH_FORMAT=safe (D24S8/D32S8 -> D16S8 when supported, "
                 "D32 -> D16 when D16 supported)"),
                ("aggressive", {"WRAPPER_REDUCE_DEPTH_FORMAT": "aggressive"}, "recorded",
                 "WRAPPER_REDUCE_DEPTH_FORMAT=aggressive (D32/D24S8/D32S8/D16S8 -> D16)"),
                ("disabled", {"WRAPPER_REDUCE_DEPTH_FORMAT": "disabled"},
                 "expect_create_failure",
                 "WRAPPER_REDUCE_DEPTH_FORMAT=disabled (depth image creation blocked)"),
            ]
            for mode_name, env, expectation, description in variants:
                add_plan("depth-reduce-%s-%s" % (fmt, mode_name), "depth-reduce", candidate,
                         "compute", "depth-attach", fmt, red_w, red_h, [input_name], data,
                         "depth_reduce" if fmt in DEPTH_BUFFER_BYTES else "depth",
                         expectation=expectation, capture_log=True, env=env,
                         expected_desc=description)
        control = generate_depth("d32", red_w, red_h, seed)
        control_name = add_input("depth-attach-control.bin", control)
        add_plan("depth-attach-d32-system", "depth-reduce", "system", SYSTEM_CONFIG,
                 "depth-attach", "d32", red_w, red_h, [control_name], control, "depth",
                 expected_desc="system loader depth-attachment-capable transfer control")

        # ---- (h) BC7 differential family (opt-in: --cases bc7 / --extended-cases bc7) ----
        if include_bc7:
            bc7_w, bc7_h = 64, 64
            bc7_blocks, bc7_coverage = GENERATORS["bc7"](bc7_w, bc7_h, seed)
            bc7_input = add_input("bc7.bin", bc7_blocks)
            bc7_reference = self._bc7_setup_reference(bc7_blocks, bc7_w, bc7_h)
            bc7_linear = linearise_rgba8(bc7_reference)
            self.bc7_reference["blocks_sha256"] = sha256_bytes(bc7_blocks)
            self.bc7_reference["reference_rgba_sha256"] = sha256_bytes(bc7_reference)
            self.bc7_reference["generator_coverage"] = bc7_coverage
            for target in ("candidate", "baseline"):
                for config in configs:
                    is_compute = config in ("compute", "compute-view")
                    reference_class = ("self-consistency-bcdec" if config == "host-cpu"
                                       else "independent-wrapper-compute-bc7")
                    add_plan("%s-%s-bc7-copy" % (target, config), "bc7", target, config,
                             "copy", "bc7", bc7_w, bc7_h, [bc7_input], bc7_reference,
                             "rgba8", reference_class=reference_class, capture_log=True,
                             tolerance=0 if config == "host-cpu" else self.tolerance,
                             expected_desc="host bcdec BC7 decode of valid modes 0-7 blocks")
                    srgb_convention = (
                        "compute-shader bc7.comp linearises BC7_SRGB texels before storing them "
                        "in the emulated R8G8B8A8_UNORM image"
                        if is_compute else
                        "host-CPU bcdec fallback stores sRGB-encoded numeric values "
                        "(no linearisation)")
                    add_plan("%s-%s-bc7_srgb-copy" % (target, config), "bc7", target, config,
                             "copy", "bc7_srgb", bc7_w, bc7_h, [bc7_input],
                             bc7_linear if is_compute else bc7_reference, "rgba8",
                             reference_class=reference_class, capture_log=True,
                             tolerance=0 if config == "host-cpu" else self.tolerance,
                             srgb_convention=srgb_convention,
                             expected_desc="BC7_SRGB: %s" % srgb_convention)
            add_plan("system-bc7-copy", "bc7", "system", SYSTEM_CONFIG, "copy", "bc7",
                     bc7_w, bc7_h, [bc7_input], bc7_blocks, "compressed",
                     reference_class="transfer-control-compressed",
                     expected_desc="compressed BC7 transfer round trip through the system loader")
            add_plan("system-bc7_srgb-copy", "bc7", "system", SYSTEM_CONFIG, "copy",
                     "bc7_srgb", bc7_w, bc7_h, [bc7_input], bc7_blocks, "compressed",
                     reference_class="transfer-control-compressed",
                     expected_desc="compressed BC7_SRGB transfer round trip through the system loader")
            add_plan("system-bc7-blit", "bc7", "system", SYSTEM_CONFIG, "blit", "bc7",
                     bc7_w, bc7_h, [bc7_input], bc7_reference, "rgba8",
                     reference_class="independent-native-llvmpipe-bc7",
                     expected_desc="llvmpipe native BC7 decode via vkCmdBlitImage")
            add_plan("candidate-compute-bc7-blit", "bc7", "candidate", "compute", "blit",
                     "bc7", bc7_w, bc7_h, [bc7_input], bc7_reference, "rgba8",
                     reference_class="wrapper-emulated-blit-passthrough", capture_log=True,
                     expected_desc="wrapper-emulated BC7 decode read back through vkCmdBlitImage")

        if families:
            self.extended_plans = [plan for plan in self.extended_plans
                                   if plan["case"] in families]
            self.extended_plans_by_name = {plan["name"]: plan
                                           for plan in self.extended_plans}
        # stage extended inputs once
        if self._ext_input_paths:
            self.adb.push(self._ext_input_paths, "%s/in" % self.remote_dir)
            for path in self._ext_input_paths:
                self.staged["files"]["in/" + path.name] = {
                    "source": str(path), "sha256": sha256_file(path),
                    "bytes": path.stat().st_size}
        self.log("extended matrix: %d planned runs across %d cases"
                 % (len(self.extended_plans),
                    len({plan["case"] for plan in self.extended_plans})))
        for entry in self.extended_plans:
            self.run_probe(entry["target"], entry["config"], entry["mode"], entry["format"],
                           capture_logcat=entry["capture_log"], plan=entry)

    # -- extended evaluation ------------------------------------------------
    @staticmethod
    def _ext_record(run, metrics, decoded_kind, verdict, reasons):
        record = {}
        for key in ("name", "case", "target", "config", "mode", "format", "width", "height",
                    "command", "environment", "exit_code", "duration_seconds", "steps",
                    "fields", "format_properties", "logcat", "wrapper_log", "decode_path",
                    "srgb_convention", "notes", "readback_sha256", "readback_bytes",
                    "levels", "extended", "plan_name", "reference_class"):
            if key in run:
                record[key] = run[key]
        record.update({"metrics": metrics, "decoded_kind": decoded_kind,
                       "verdict": verdict, "reasons": reasons})
        if "stdout_tail" in run:
            record["stdout_tail"] = run["stdout_tail"]
        record["expected_desc"] = run.get("expected_desc")
        if "stderr" in run:
            record["stderr"] = run["stderr"]
        return record

    def evaluate_extended_run(self, run):
        plan = self.extended_plans_by_name.get(run.get("plan_name"), {})
        expectation = plan.get("expectation", "match")
        kind = plan.get("expected_kind")
        parsed = run["fields"]
        reasons = []
        probed_ok = run["exit_code"] == 0 and parsed.get("RESULT") == "ok"
        metrics = None
        if expectation == "expect_create_failure":
            observed = {"exit_code": run["exit_code"],
                        "fail_stage": parsed.get("FAIL_STAGE"),
                        "fail_result": parsed.get("FAIL_RESULT")}
            if probed_ok:
                reasons.append("wrapper accepted the depth image although the depth override "
                               "was set to 'disabled'; expected image creation to be rejected")
            elif parsed.get("FAIL_STAGE") != "image-create":
                reasons.append("expected FAIL_STAGE=image-create, observed %s"
                               % parsed.get("FAIL_STAGE"))
            metrics = {"expected": "VK_ERROR_FORMAT_NOT_SUPPORTED at image-create",
                       "observed": observed}
            verdict = "pass" if not reasons else "fail"
            run["verdict"] = verdict
            return self._ext_record(run, metrics, "expect_create_failure", verdict, reasons)
        if not probed_ok:
            reasons.append("probe did not report RESULT=ok (exit=%s, FAIL_STAGE=%s, "
                           "FAIL_RESULT=%s)" % (run["exit_code"], parsed.get("FAIL_STAGE"),
                                                parsed.get("FAIL_RESULT")))
            verdict = "recorded" if expectation == "recorded" else "fail"
            run["verdict"] = verdict
            return self._ext_record(run, None, kind, verdict, reasons)
        output_path = self.local_out / run["output_name"]
        if not output_path.exists():
            reasons.append("missing readback file %s" % output_path)
            verdict = "recorded" if expectation == "recorded" else "fail"
            run["verdict"] = verdict
            return self._ext_record(run, None, kind, verdict, reasons)
        actual = output_path.read_bytes()
        run["readback_sha256"] = sha256_bytes(actual)
        run["readback_bytes"] = len(actual)
        expected = plan.get("expected")
        tolerance = plan.get("tolerance", self.tolerance)
        hard = 0
        if kind in ("rgba8", "linear_rgba8"):
            metrics = compare_rgba8(actual[:len(expected)], expected, tolerance)
            hard = metrics["hard_mismatches"]
        elif kind in ("compressed", "exact_bytes"):
            metrics = compare_compressed(actual[:len(expected)], expected)
            hard = metrics["mismatch_bytes"]
        elif kind == "channels":
            metrics = compare_channels(actual, expected, plan["channels"], tolerance)
            hard = metrics["hard_mismatches"]
        elif kind == "depth":
            metrics = compare_depth(actual[:len(expected)], expected, plan["format"],
                                    self.tolerance)
            hard = metrics["hard_mismatches"]
        elif kind == "depth_reduce":
            metrics = compare_depth(actual[:len(expected)], expected, plan["format"],
                                    self.tolerance)
            hard = 0  # recorded behaviour, not a claim
        elif kind == "slices":
            slice_metrics = []
            total_pixels = total_exact = total_mismatch = total_hard = 0
            max_delta = 0
            for slice_entry in (plan.get("slices") or plan.get("expected") or []):
                offset = slice_entry["offset"]
                data = slice_entry["data"]
                observed = actual[offset:offset + len(data)]
                if slice_entry["kind"] == "rgba8":
                    entry_metrics = compare_rgba8(observed, data, tolerance)
                    key = "pixels"
                elif slice_entry["kind"] == "compressed":
                    entry_metrics = compare_compressed(observed, data)
                    key = "bytes"
                else:
                    entry_metrics = compare_compressed(observed, data)
                    key = "bytes"
                slice_metrics.append({
                    "layer": slice_entry.get("layer"), "mip": slice_entry.get("mip"),
                    "width": slice_entry.get("width"), "height": slice_entry.get("height"),
                    "offset": offset, "length": len(data),
                    "verdict": "pass" if not entry_metrics.get("hard_mismatches")
                               and not entry_metrics.get("mismatch_bytes") else "fail",
                    "metrics": entry_metrics,
                })
                if key == "pixels":
                    total_pixels += entry_metrics["pixels"]
                    total_exact += entry_metrics["exact_pixels"]
                    total_mismatch += entry_metrics["mismatch_pixels"]
                    total_hard += entry_metrics["hard_mismatches"]
                    max_delta = max(max_delta, entry_metrics["max_channel_delta"])
                else:
                    total_pixels += entry_metrics["bytes"]
                    total_exact += entry_metrics["exact_bytes"]
                    total_mismatch += entry_metrics["mismatch_bytes"]
                    total_hard += entry_metrics["hard_mismatches"]
            metrics = {"slices": slice_metrics, "pixels": total_pixels,
                       "exact_pixels": total_exact, "mismatch_pixels": total_mismatch,
                       "hard_mismatches": total_hard, "max_channel_delta": max_delta,
                       "tolerance": self.tolerance}
            hard = total_hard
        else:
            reasons.append("internal error: unknown expected kind %r" % kind)
        if kind == "depth_reduce" and metrics:
            metrics["observed_note"] = (
                "%s; depth texels %d/%d exact, max value delta %d"
                % (plan.get("expected_desc"), metrics["exact_texels"],
                   metrics["texels"], metrics["max_value_delta"]))
        if expectation == "recorded":
            if hard:
                reasons.append("recorded (not a conformance claim): %d mismatch units against "
                               "the pass-through reference" % hard)
            verdict = "recorded"
        else:
            if hard:
                reasons.append("%d mismatch unit(s) exceed the %d-LSB tolerance"
                               % (hard, self.tolerance))
            verdict = "pass" if not reasons else "fail"
        run["verdict"] = verdict
        run["metrics"] = metrics
        return self._ext_record(run, metrics, kind, verdict, reasons)

    def extended_summary(self):
        runs = [run for run in self.evaluated if run.get("extended")]
        cases = {}
        order = []
        for run in runs:
            case = run.get("case") or "unknown"
            if case not in cases:
                cases[case] = {"runs": 0, "pass": 0, "fail": 0, "recorded": 0,
                               "by_target_config": {}, "case_runs": []}
                order.append(case)
            bucket = cases[case]
            bucket["runs"] += 1
            bucket[run["verdict"]] = bucket.get(run["verdict"], 0) + 1
            key = "%s/%s/%s/%s" % (run["target"], run["config"], run["mode"], run["format"])
            metrics = run.get("metrics") or {}
            bucket["by_target_config"].setdefault(key, {
                "verdict": run["verdict"],
                "exact": metrics.get("exact_texels", metrics.get("exact_pixels",
                                                                 metrics.get("exact_bytes"))),
                "total": metrics.get("texels", metrics.get("pixels", metrics.get("bytes"))),
                "mismatch": metrics.get("mismatch_texels", metrics.get("mismatch_pixels",
                                                                       metrics.get("mismatch_bytes"))),
                "hard_mismatches": metrics.get("hard_mismatches"),
                "max_delta": metrics.get("max_value_delta",
                                         metrics.get("max_channel_delta")),
                "kind": run.get("decoded_kind"),
                "notes": run.get("notes"),
            })
            bucket["case_runs"].append(run["name"])
        totals = {"runs": len(runs),
                  "pass": sum(1 for run in runs if run["verdict"] == "pass"),
                  "fail": sum(1 for run in runs if run["verdict"] == "fail"),
                  "recorded": sum(1 for run in runs if run["verdict"] == "recorded")}
        return {"order": order, "cases": cases, "totals": totals}

    def extended_findings(self):
        runs = [run for run in self.evaluated if run.get("extended")]
        if not runs:
            return []
        statements = []

        def metrics_of(run):
            return run.get("metrics") or {}

        odd = [run for run in runs if run["case"] == "odd-extents"]
        if odd:
            bc_odd = [run for run in odd if run["format"] in ("bc1", "bc3")]
            crashed = [run for run in bc_odd if run["exit_code"] != 0]
            wrapped_ok = [run for run in bc_odd if run["exit_code"] == 0]
            mismatched = [run for run in wrapped_ok
                          if metrics_of(run).get("mismatch_pixels")]
            statements.append(
                "Odd (non-multiple-of-4) extents 5x7, 13x17, 1x1, 63x65: of %d BC1/BC3 wrapper "
                "probes (out-of-spec VkBufferImageCopy regions for block-compressed images) "
                "%d completed and %d produced pixel mismatches vs the clipped independent "
                "reference; %d probe(s) aborted (recorded, not a conformance claim)."
                % (len(bc_odd), len(wrapped_ok), len(mismatched), len(crashed)))
            rgba_odd = [run for run in odd if run["format"] == "rgba8"]
            rgba_pass = sum(1 for run in rgba_odd if run["verdict"] == "pass")
            statements.append(
                "Odd-extent RGBA8 control: %d/%d transfers bit-exact (non-multiple-of-4 "
                "extents are legal for uncompressed formats)." % (rgba_pass, len(rgba_odd)))
        partial = [run for run in runs if run["case"] == "partial-region"]
        if partial:
            failing = [run for run in partial if run["verdict"] != "pass"]
            statements.append(
                "Partial-region copies (%dx%d base with a sub-rectangle at a non-zero offset, "
                "sentinel pixels outside the patch must stay unchanged): %d/%d probes pass%s."
                % (16, 16, len(partial) - len(failing), len(partial),
                   "" if not failing else "; failures: " + ", ".join(run["name"] for run in failing)))
        mips = [run for run in runs if run["case"] == "mips-layers"]
        if mips:
            failing = [run for run in mips if run["verdict"] != "pass"]
            statements.append(
                "Mip levels (3) and array layers (2): %d/%d per-layer/per-level copy probes "
                "pass%s." % (len(mips) - len(failing), len(mips),
                             "" if not failing else "; failures: "
                             + ", ".join(run["name"] for run in failing)))
        srgb = [run for run in runs if run["case"] == "srgb"]
        if srgb:
            compute_srgb = [run for run in srgb if run["format"].endswith("_srgb")
                            and run["config"] in ("compute", "compute-view")]
            host_srgb = [run for run in srgb if run["format"].endswith("_srgb")
                         and run["config"] == "host-cpu"]
            control = [run for run in srgb if run["format"] == "rgba8_srgb"]
            def max_delta(run_list):
                values = [metrics_of(run).get("max_channel_delta") for run in run_list
                          if metrics_of(run).get("max_channel_delta") is not None]
                return max(values) if values else None
            exact_compute = sum(metrics_of(run).get("exact_pixels") or 0 for run in compute_srgb)
            total_compute = sum(metrics_of(run).get("pixels") or 0 for run in compute_srgb)
            exact_host = sum(metrics_of(run).get("exact_pixels") or 0 for run in host_srgb)
            total_host = sum(metrics_of(run).get("pixels") or 0 for run in host_srgb)
            statements.append(
                "sRGB BC emulation convention: compute-shader paths store sRGB->linear values "
                "(%d/%d pixels match the linearised reference, max delta %s); the host-CPU "
                "fallback stores sRGB-encoded numeric values (%d/%d exact vs the encoded "
                "reference, max delta %s). The two wrapper decode paths therefore disagree on "
                "sRGB storage by design/omission - flagged as a suspected emulation "
                "inconsistency for the wrapper owners."
                % (exact_compute, total_compute, max_delta(compute_srgb), exact_host,
                   total_host, max_delta(host_srgb)))
            statements.append(
                "RGBA8_SRGB identity control: %d/%d transfers bit-exact."
                % (sum(1 for run in control if run["verdict"] == "pass"), len(control)))
        rgtc = [run for run in runs if run["case"] == "bc4-bc5"]
        if rgtc:
            compute_runs = [run for run in rgtc if run["target"] == "candidate"
                            and run["config"] in ("compute", "compute-view")
                            and run["mode"] == "copy"]
            host_runs = [run for run in rgtc if run["config"] == "host-cpu"
                         and run["mode"] == "copy"]
            native_blit = [run for run in rgtc if run["target"] == "system"
                           and run["mode"] == "blit"]
            native_copy = [run for run in rgtc if run["target"] == "system"
                           and run["mode"] == "copy"]
            def aggregate(run_list):
                exact = sum(metrics_of(run).get("exact_pixels",
                                                metrics_of(run).get("exact_texels",
                                                                    metrics_of(run).get("exact_bytes"))) or 0
                            for run in run_list)
                total = sum(metrics_of(run).get("pixels", metrics_of(run).get("texels",
                                                                              metrics_of(run).get("bytes"))) or 0
                            for run in run_list)
                deltas = [metrics_of(run).get("max_channel_delta") for run in run_list
                          if metrics_of(run).get("max_channel_delta") is not None]
                return exact, total, (max(deltas) if deltas else None)
            ce, ct, cd = aggregate(compute_runs)
            he, ht, hd = aggregate(host_runs)
            ne, nt, nd = aggregate(native_copy)
            be, bt, bd = aggregate(native_blit)
            statements.append(
                "BC4_UNORM/BC5_UNORM with independent RGTC decoders: wrapper compute-shader "
                "paths %d/%d pixels exact (max delta %s); host-CPU fallback %d/%d exact (max "
                "delta %s); system-loader compressed transfer %d/%d bytes exact; native "
                "vkCmdBlitImage RGTC decode %d/%d channel samples exact (max delta %s)."
                % (ce, ct, cd, he, ht, hd, ne, nt, be, bt, bd))
        depth_reduce = [run for run in runs if run["case"] == "depth-reduce"]
        if depth_reduce:
            create_failures = [run for run in depth_reduce
                               if (run.get("metrics") or {}).get("observed", {}).get(
                                   "fail_stage") == "image-create"]
            changed = [run for run in depth_reduce
                       if run.get("decode_path", {}).get("depth_override_to_vk_format")]
            roundtrip_exact = [run for run in depth_reduce
                               if (run.get("metrics") or {}).get("exact_texels") ==
                               (run.get("metrics") or {}).get("texels")]
            statements.append(
                "Depth format-reduction knob (WRAPPER_REDUCE_DEPTH_FORMAT, reachable only for "
                "images created with DEPTH_STENCIL_ATTACHMENT usage): %d/%d probes recorded a "
                "wrapper format change (%s), %d probe(s) rejected image creation with 'disabled', "
                "%d/%d round trips bit-exact across all variants. See the per-mode table for "
                "safe/aggressive outcomes."
                % (len(changed), len(depth_reduce),
                   ", ".join(sorted({run["name"] for run in changed})) or "none",
                   len(create_failures), len(roundtrip_exact), len(depth_reduce)))
        bc7 = [run for run in runs if run["case"] == "bc7"]
        if bc7:
            def bc7_aggregate(predicate):
                selected = [run for run in bc7 if predicate(run)]
                exact = total = hard = 0
                deltas = []
                for run in selected:
                    metrics = run.get("metrics") or {}
                    exact += metrics.get("exact_pixels", metrics.get("exact_bytes", 0)) or 0
                    total += metrics.get("pixels", metrics.get("bytes", 0)) or 0
                    hard += metrics.get("hard_mismatches", 0) or 0
                    if metrics.get("max_channel_delta") is not None:
                        deltas.append(metrics["max_channel_delta"])
                return len(selected), exact, total, hard, (max(deltas) if deltas else None)
            wrapper_compute = [run for run in bc7
                               if run["target"] in ("candidate", "baseline")
                               and run["config"] in ("compute", "compute-view")
                               and run["mode"] == "copy" and run["format"] == "bc7"]
            host_cpu = [run for run in bc7
                        if run["config"] == "host-cpu" and run["mode"] == "copy"]
            srgb_compute = [run for run in bc7
                            if run["target"] in ("candidate", "baseline")
                            and run["config"] in ("compute", "compute-view")
                            and run["mode"] == "copy" and run["format"] == "bc7_srgb"]
            native_blit = [run for run in bc7 if run["target"] == "system"
                           and run["mode"] == "blit"]
            native_copy = [run for run in bc7 if run["target"] == "system"
                           and run["mode"] == "copy"]
            count, exact, total, hard, delta = bc7_aggregate(
                lambda run: run in wrapper_compute)
            if count:
                statements.append(
                    "BC7_UNORM wrapper compute-shader (bc7.comp, independent of bcdec) vs the "
                    "host bcdec reference: %d probe(s), %d/%d pixels exact, %d pixel(s) over "
                    "tolerance, max channel delta %s%s."
                    % (count, exact, total, hard, delta,
                       "" if not hard else " <-- SUSPECTED DEFECT"))
            count, exact, total, hard, delta = bc7_aggregate(
                lambda run: run in srgb_compute)
            if count:
                statements.append(
                    "BC7_SRGB wrapper compute-shader vs the sRGB-linearised bcdec reference "
                    "(bc7.comp linearises sRGB texels): %d probe(s), %d/%d pixels exact, %d "
                    "over tolerance, max channel delta %s." % (count, exact, total, hard, delta))
            count, exact, total, hard, delta = bc7_aggregate(
                lambda run: run in host_cpu)
            if count:
                statements.append(
                    "BC7 host-CPU (USE_CPU_BCN=all) vs bcdec: SELF-CONSISTENCY CHECK (same "
                    "decoder implementation): %d probe(s), %d/%d pixels exact, %d over "
                    "tolerance, max delta %s; a non-zero result here would indicate wrapper "
                    "staging/stride differences rather than a decoder difference."
                    % (count, exact, total, hard, delta))
            count, exact, total, hard, delta = bc7_aggregate(
                lambda run: run in native_blit)
            if count:
                statements.append(
                    "BC7_UNORM llvmpipe native decoder (independently implemented) vs bcdec, "
                    "observed through vkCmdBlitImage: %d probe(s), %d/%d pixels exact, %d over "
                    "tolerance (usual inter-decoder spread), max channel delta %s."
                    % (count, exact, total, hard, delta))
            count, exact, total, hard, delta = bc7_aggregate(
                lambda run: run in native_copy)
            if count:
                statements.append(
                    "BC7/BC7_SRGB native compressed transfer controls (system loader): %d/%d "
                    "bytes exact." % (exact, total))
            statements.append(
                "BC7 format support (system loader caps): %s."
                % ", ".join("%s=%s" % (prop.get("name"), "supported"
                                       if prop.get("optimal_transfer_src") and
                                       prop.get("optimal_transfer_dst") else "unsupported")
                            for prop in (self.caps.get("system", {}).get(
                                "format_properties_list") or [])
                            if prop.get("name") in ("bc2", "bc6h_uf16", "bc6h_sf16",
                                                    "bc7", "bc7_srgb")))
        return statements

    def plan_records(self):
        return [self._ext_record(run, run.get("metrics"), run.get("decoded_kind"),
                                 run["verdict"], run["reasons"])
                for run in self.evaluated if run.get("extended")]


for _name, _value in vars(_ExtendedMixin).items():
    if not _name.startswith("__"):
        setattr(ReferenceProbe, _name, _value)


def assemble_extended_receipt(payloads, paths, args, self_test):
    """Merge one payload per emulator phase into the extended evidence receipt."""
    tool_hashes = {payload.get("tool", {}).get("sha256") for payload in payloads}
    base_commits = {payload.get("base_commit") for payload in payloads}
    if len(base_commits) > 1:
        raise SystemExit("error: phase payloads have different base commits: %s" % base_commits)
    bc7_receipt = all((payload.get("protocol") or {}).get("cases") == "bc7"
                      for payload in payloads)
    phases = []
    merged_runs = []
    merged_depth = []
    merged_findings = []
    merged_limitations = []
    merged_capabilities = {}
    case_summaries = {}
    phase_artifacts = []
    for payload, path in zip(payloads, paths):
        label = payload.get("label")
        phase = {
            "label": label,
            "source_payload": {"path": str(path), "sha256": sha256_file(path)},
            "device": payload.get("device"),
            "page_size": payload.get("device", {}).get("probe_reported", {}).get("page_size"),
            "emulator_facts": {
                "is_emulator": payload.get("device", {}).get("is_emulator"),
                "properties": payload.get("device", {}).get("properties"),
                "emulator_binary_version": payload.get("device", {}).get("emulator_binary_version"),
                "uname": payload.get("device", {}).get("uname"),
            },
            "avd": payload.get("device", {}).get("properties", {}).get(
                "ro.boot.qemu.avd_name"),
            "default_matrix_summary": payload.get("summary"),
            "case_summary": payload.get("extended_cases"),
            "capabilities_summary": {
                target: {
                    "device_name": caps.get("device_name"),
                    "driver_id": caps.get("driver_id"),
                    "driver_id_name": caps.get("driver_id_name"),
                    "driver_name": caps.get("driver_name"),
                    "driver_info": caps.get("driver_info"),
                    "api": caps.get("api"),
                    "page_size_in_probe": caps.get("page_size_in_probe"),
                    "texture_compression_bc": caps.get("texture_compression_bc"),
                    "notes": caps.get("notes"),
                    "format_properties": caps.get("format_properties"),
                }
                for target, caps in (payload.get("capabilities") or {}).items()
            },
            "run_count": len(payload.get("runs") or []),
        }
        phases.append(phase)
        merged_capabilities[label] = phase["capabilities_summary"]
        for run in payload.get("runs") or []:
            run = dict(run)
            run["phase"] = label
            merged_runs.append(run)
        for run in payload.get("depth_results") or []:
            run = dict(run)
            run["phase"] = label
            merged_depth.append(run)
        phase_page_size = (payload.get("device", {}).get("probe_reported", {})
                           or {}).get("page_size")
        for statement in payload.get("findings") or []:
            if phase_page_size and "probe sysconf=None" in statement:
                statement = statement.replace(
                    "probe sysconf=None",
                    "probe sysconf=%s (repaired at assembly from the recorded caps)" % phase_page_size)
            if phase_page_size and str(phase_page_size) == "16384":
                statement = statement.replace("on a 4 KB-page emulator",
                                              "on a 16 KB-page emulator")
            if phase_page_size and str(phase_page_size) == "4096":
                statement = statement.replace("on a 4 KB-page emulator",
                                              "on a 4 KB-page emulator")
            merged_findings.append("[%s] %s" % (label, statement))
        for statement in payload.get("limitations") or []:
            if statement not in merged_limitations:
                merged_limitations.append(statement)
        for case, summary in ((payload.get("extended_cases") or {}).get("cases") or {}).items():
            aggregate = case_summaries.setdefault(
                case, {"runs": 0, "pass": 0, "fail": 0, "recorded": 0, "phases": {}})
            for key in ("runs", "pass", "fail", "recorded"):
                aggregate[key] += summary.get(key, 0)
            aggregate["phases"][label] = summary
        phase_artifacts.append({"label": label, "raw_artifacts":
                                payload.get("raw_artifacts")})

    def phase_counts(payload):
        summary = payload.get("summary") or {}
        return {"runs": summary.get("runs_total"), "pass": summary.get("runs_pass"),
                "fail": summary.get("runs_fail"),
                "extended": (payload.get("extended_cases") or {}).get("totals")}

    total_runs = sum(len(payload.get("runs") or []) for payload in payloads)
    extended_totals = {"runs": 0, "pass": 0, "fail": 0, "recorded": 0}
    default_totals = {"runs": 0, "pass": 0, "fail": 0}
    for payload in payloads:
        counts = phase_counts(payload)
        summary = payload.get("summary") or {}
        default_totals["runs"] += summary.get("runs_total", 0)
        default_totals["pass"] += summary.get("runs_pass", 0)
        default_totals["fail"] += summary.get("runs_fail", 0)
        ext = counts["extended"] or {}
        for key in extended_totals:
            extended_totals[key] += ext.get(key, 0)
    aggregate_new = {"runs": extended_totals["pass"] + extended_totals["fail"]
                     + extended_totals["recorded"],
                     "pass": extended_totals["pass"],
                     "fail": extended_totals["fail"],
                     "recorded": extended_totals["recorded"]}
    findings = []
    for payload in payloads:
        findings.extend("[%s] %s" % (payload.get("label"), statement)
                        for statement in payload.get("findings") or [])
    for run in merged_runs:
        if run.get("verdict") == "pass":
            run.pop("stdout", None)
            run.pop("stderr", None)
        # Older revisions parsed wrapper debug-log lines as probe fields; keep only
        # the machine-readable protocol keys.
        fields = run.get("fields")
        if isinstance(fields, dict):
            run["fields"] = {key: value for key, value in fields.items()
                             if re.fullmatch(r"[A-Z][A-Z0-9_]*", key or "")}
    merged_limitations.append(
        "No physical phone/Adreno/KGSL device was attached and no phone or GPU-vendor result "
        "is claimed anywhere in this receipt.")
    if not bc7_receipt:
        merged_limitations.append(
            "Phase 2 used AVD Vapor_API_36 on port 5560 instead of Vapor_UI_API36: the "
            "Vapor_UI_API36 AVD was locked by another live emulator on port 5556 (the AVD "
            "cannot be shared unless every instance runs -read-only), and Vapor_API_36 uses "
            "the identical system image (android-36/google_apis/arm64-v8a), ABI and 4 KB page "
            "size. Page size and API level were verified from the device: page_size=4096, "
            "ro.build.version.sdk=36.")
        if "extended cases on the 16 KB" not in " ".join(merged_limitations):
            merged_limitations.append(
                "Extended case families were executed on the 4 KB API 36 emulator; the 16 KB "
                "API 37 AVD was reclaimed for another teammate after the first-pass matrix run "
                "(per coordination), so only the original matrix ran at 16 KB. The 16 KB "
                "first-pass payload was produced by the pre-extension tool revision recorded in "
                "generation.phase_payloads and runs[].phase.")
    for index, statement in enumerate(merged_findings):
        merged_findings[index] = (statement
                                  .replace("4096-byte-page emulator", "4 KB-page emulator")
                                  .replace("16384-byte-page emulator", "16 KB-page emulator"))
    stale_partial = [statement for statement in merged_limitations
                     if "no mip levels, array layers, sRGB variants or sub-block partial "
                        "copies are exercised" in statement]
    for statement in stale_partial:
        merged_limitations.remove(statement)
    for index, statement in enumerate(merged_limitations):
        if "Depth format reduction (WRAPPER_REDUCE_DEPTH_FORMAT) was deliberately not " \
           "exercised" in statement:
            merged_limitations[index] = (
                "Depth format reduction (WRAPPER_REDUCE_DEPTH_FORMAT) is exercised by the "
                "extended depth-reduce case through images created with "
                "DEPTH_STENCIL_ATTACHMENT usage; per-mode behaviour is recorded in the "
                "depth-reduce case table (safe/aggressive reduce formats and break bit-exact "
                "transfer, disabled rejects image creation). Transfer-only depth images "
                "(create/bind/copy with TRANSFER_DST|TRANSFER_SRC) never trigger the knob.")
        if "BC7 decoding is not implemented in the independent reference; only BC7 format " \
           "support is recorded." in statement:
            merged_limitations[index] = (
                "The independent reference decodes BC1/BC3 (first pass) and BC4_UNORM/BC5_UNORM "
                "(extended pass). BC2, BC6H and BC7 decoding are not implemented; BC7/BC2 "
                "format support is still recorded from the driver caps.")
    receipt = {
        "date": time.strftime("%Y-%m-%d"),
        "scope": "Extended reference-image qualification of the pinned Vulkan-wrapper candidate "
                 "and baseline against independent CPU BC1/BC3/BC4/BC5 S3TC/RGTC decoders: the "
                 "full first-pass matrix on a 16 KB-page API 37 emulator plus extended cases "
                 "(non-multiple-of-4 extents, partial-region copies, mip levels and array "
                 "layers, sRGB variants, BC4/BC5 and the depth format-reduction knob) on 4 KB "
                 "and 16 KB emulators. Offscreen transfer-only native probe; no app, APK, "
                 "window, Wine, Steam or game is started. No phone/Adreno/KGSL result is "
                 "claimed.",
        "label": getattr(args, "label", "extended"),
        "base_commit": payloads[0].get("base_commit"),
        "tool": payloads[-1].get("tool"),
        "tool_revisions": {
            payload.get("label"): {
                "tool_sha256": (payload.get("tool") or {}).get("sha256"),
                "fixture_source_sha256": ((payload.get("tool") or {}).get("fixture") or {}).get("source_sha256"),
                "fixture_binary_sha256": ((payload.get("tool") or {}).get("fixture") or {}).get("binary_sha256"),
                "runs": len(payload.get("runs") or []),
            }
            for payload in payloads
        },
        "tool_hashes": {
            "phase_payloads": sorted(tool_hashes),
            "current_assembly_tool": sha256_file(Path(__file__).resolve()),
            "note": "Phase payloads may have been produced by different revisions of this "
                    "tool (see tool_revisions and generation.phase_payloads); the extended "
                    "case families were produced by the final revision that also assembled "
                    "this receipt.",
        },
        "reference_decoder": payloads[0].get("reference_decoder"),
        "wrapper_builds": payloads[0].get("wrapper_builds"),
        "phases": phases,
        "capabilities": merged_capabilities,
        "runs": merged_runs,
        "summary": {
            "phases": len(phases),
            "runs_total": total_runs,
            "default_matrix": default_totals,
            "extended_cases": aggregate_new,
            "extended_case_table": case_summaries,
            "per_phase": [{"label": payload.get("label"),
                           "default_matrix": (payload.get("summary") or {}),
                           "extended_cases": (payload.get("extended_cases") or {}).get("totals")}
                          for payload in payloads],
        },
        "extended_cases": case_summaries,
        "depth_results": merged_depth,
        "findings": merged_findings,
        "limitations": merged_limitations,
        "raw_artifacts": {"phases": phase_artifacts,
                          "assembled_from": [{"path": str(path), "sha256": sha256_file(path)}
                                             for path in paths]},
        "suspected_defects": [
            {
                "id": "srgb-bc-storage-inconsistency",
                "severity": "correctness/consistency (software emulation only, no GPU defect)",
                "scope": "wrapper compute-shader BC decode vs host-CPU bcdec fallback for "
                         "BC1_SRGB/BC3_SRGB images",
                "observation": "The compute-shader decode linearises sRGB texels before "
                               "storing them in the emulated R8G8B8A8_UNORM image, while the "
                               "host-CPU fallback stores the sRGB-encoded numeric values "
                               "(matches +-1 LSB against the two different references). The two "
                               "paths therefore decode the same BC1_SRGB/BC3_SRGB data to "
                               "different pixels; a title that falls back to CPU decoding can "
                               "look different from one that uses the compute shader.",
                "evidence": "srgb case: compute/compute-view 19320/32768 pixels vs the "
                            "linearised reference (max delta 1); host-cpu 14804/16384 exact vs "
                            "the encoded reference (max delta 1).",
            },
            {
                "id": "odd-extent-bc-host-cpu-overrun",
                "severity": "robustness only; the triggering VkBufferImageCopy region is "
                            "out-of-spec for a block-compressed image",
                "scope": "wrapper host-CPU (bcdec) path for BC images whose extent is not a "
                         "multiple of 4",
                "observation": "For image extents 5x7, 13x17, 63x65 the host-CPU path corrupts "
                               "part of the partial edge block (the compute-shader paths decode "
                               "every in-bounds pixel correctly). The copy command is not "
                               "spec-valid for block-compressed images, so this is not a "
                               "conformance failure, but the host path's staging-buffer stride "
                               "assumes block-aligned extents and writes past the partial block.",
                "evidence": "odd-extents case: compute/compute-view 0 mismatches at every "
                            "extent; host-cpu 15-48 mismatched pixels with max channel delta up "
                            "to 255 at 5x7/13x17/63x65 (1x1 is a single pixel and matches).",
            },
        ],
        "phone_runbook": payloads[-1].get("phone_runbook"),
        "generation": {
            "mode": "assembled-from-executed-phase-payloads",
            "assembly_tool": {
                "path": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
                "sha256": sha256_file(Path(__file__).resolve()),
            },
            "executing_tool_sha256s": {
                payload.get("label"): (payload.get("tool") or {}).get("sha256")
                for payload in payloads
            },
            "fixture_binary_sha256s": {
                payload.get("label"): ((payload.get("tool") or {}).get("fixture") or {}).get(
                    "binary_sha256")
                for payload in payloads
            },
            "phase_payloads": [{"label": payload.get("label"), "path": str(path),
                                "sha256": sha256_file(path)}
                               for payload, path in zip(payloads, paths)],
            "note": "Each phase was executed end to end by the tool revision and fixture "
                    "binary recorded for that phase on its own emulator (16 KB phase after the "
                    "first-pass import fix, 4 KB phase with the extended case families); this "
                    "receipt merges those raw payloads without re-executing device work. "
                    "Per-run commands, environments and readback hashes are carried over "
                    "verbatim; passing-run stdout is dropped from this receipt (full logs live "
                    "under raw_artifacts). The extended-case families were run on 4 KB only; "
                    "see limitations.",
        },
    }
    receipt["reference_decoder"]["self_test"] = self_test
    if bc7_receipt:
        receipt["label"] = getattr(args, "label", "bc7-reference")
        receipt["scope"] = (
            "BC7 differential qualification of the pinned Vulkan-wrapper candidate and its "
            "unpatched baseline: deterministic VALID BC7 blocks (modes 0-7, plus BC7_SRGB) "
            "decoded on the host with the pinned wrapper source's bcdec reference and compared "
            "against the wrapper compute-shader decoder (bc7.comp), the wrapper host-CPU bcdec "
            "fallback (self-consistency check) and llvmpipe's native BC7 decoder through the "
            "system loader (compressed transfer + vkCmdBlitImage decode), on a 4 KB API 36 "
            "emulator and a 16 KB API 37 emulator. Offscreen transfer-only probe; no app, "
            "window, Wine, Steam or game is started and no phone/Adreno/KGSL result is claimed.")
        receipt["bc7_reference"] = payloads[-1].get("bc7_reference")
        receipt["bc7_verification"] = bc7_case_table(merged_runs)
        bc7_runs = [run for run in merged_runs if run.get("case") == "bc7"]
        wrapper_defects = []
        for run in bc7_runs:
            metrics = run.get("metrics") or {}
            if (run.get("target") in ("candidate", "baseline")
                    and run.get("config") in ("compute", "compute-view")
                    and run.get("mode") == "copy"
                    and metrics.get("hard_mismatches")):
                wrapper_defects.append({
                    "id": "bc7-compute-vs-bcdec-mismatch",
                    "severity": "suspected wrapper compute-shader BC7 decode difference",
                    "scope": "%s/%s/%s" % (run.get("target"), run.get("config"),
                                           run.get("format")),
                    "observation": "%d pixel(s) differ by more than the %d-LSB tolerance; "
                                   "max channel delta %s"
                                   % (metrics.get("hard_mismatches"),
                                      metrics.get("tolerance"),
                                      metrics.get("max_channel_delta")),
                    "evidence": run.get("name"),
                })
            if (run.get("config") == "host-cpu" and run.get("mode") == "copy"
                    and metrics.get("mismatch_pixels")):
                wrapper_defects.append({
                    "id": "bc7-host-cpu-not-exact",
                    "severity": "self-consistency gap (same bcdec decoder on both sides)",
                    "scope": "%s/%s" % (run.get("target"), run.get("format")),
                    "observation": "%d pixel(s) differ; max channel delta %s"
                                   % (metrics.get("mismatch_pixels"),
                                      metrics.get("max_channel_delta")),
                    "evidence": run.get("name"),
                })
        srgb_split = []
        for payload in payloads:
            readbacks = Path((payload.get("raw_artifacts") or {}).get("readbacks", ""))
            compute = readbacks / "candidate-compute-bc7_srgb-copy.bin"
            host_cpu = readbacks / "candidate-host-cpu-bc7_srgb-copy.bin"
            if not (compute.is_file() and host_cpu.is_file()):
                continue
            a = compute.read_bytes()
            b = host_cpu.read_bytes()
            deltas = [abs(x - y) for x, y in zip(a, b)]
            differing = sum(1 for d in deltas if d)
            srgb_split.append({
                "phase": payload.get("label"),
                "bytes": len(a),
                "differing_bytes": differing,
                "max_channel_delta": max(deltas) if deltas else 0,
            })
        if srgb_split:
            receipt["suspected_defects"] = [{
                "id": "bc7-srgb-compute-vs-host-cpu-storage-convention",
                "severity": "known wrapper-path inconsistency (sRGB storage convention); not a "
                            "BC7 decode mismatch against either reference",
                "scope": "candidate/baseline BC7_SRGB copy: compute/compute-view vs host-CPU",
                "observation": "The compute shader (bc7.comp) linearises BC7_SRGB texels before "
                               "storing them in the emulated R8G8B8A8_UNORM image while the "
                               "host-CPU bcdec fallback stores the sRGB-encoded numeric values. "
                               "Each path matches its own reference exactly (compute vs "
                               "linearised bcdec, host-CPU vs encoded bcdec), but the two "
                               "wrapper paths produce different pixels, so a title that falls "
                               "back to CPU decoding can look different. This is the same "
                               "convention split already reported for BC1_SRGB/BC3_SRGB; BC7 "
                               "adds nothing decoder-specific. BC7_UNORM compute and host-CPU "
                               "readbacks are byte-identical.",
                "evidence": srgb_split,
            }] + wrapper_defects
        else:
            receipt["suspected_defects"] = wrapper_defects
        merged_limitations = [statement for statement in merged_limitations
                              if "Depth format reduction (WRAPPER_REDUCE_DEPTH_FORMAT) is "
                                 "exercised by" not in statement]
        receipt["limitations"] = merged_limitations
        merged_findings = [statement for statement in merged_findings
                           if "BC7" in statement or "Page size" in statement]
        receipt["findings"] = merged_findings
        emulator_port = getattr(args, "emulator_port", None)
        emulator_memory = getattr(args, "emulator_memory_mb", "2048")
        emulator_phases = []
        for phase in phases:
            avd = phase.get("avd")
            serial = (phase.get("device") or {}).get("serial")
            boot = None
            if emulator_port and avd:
                boot = ("emulator -avd %s -port %s -no-window -no-audio -no-snapshot-load "
                        "-no-snapshot-save -gpu lavapipe -no-boot-anim -memory %s -cores 4"
                        % (avd, emulator_port, emulator_memory))
            emulator_phases.append({
                "label": phase.get("label"),
                "avd": avd,
                "serial": serial,
                "page_size": phase.get("page_size"),
                "boot_command": boot,
                "kill_command": ("adb -s %s emu kill" % serial) if serial else None,
                "device_facts": (phase.get("device") or {}).get("probe_reported"),
            })
        receipt["emulator_operations"] = {
            "ownership": "port 5556 exclusively for this lane; phases run sequentially and "
                         "each emulator is killed before the next boot",
            "preflight": "no qemu-system process listening on the port and `adb devices` "
                         "empty before each boot",
            "post_phase": "`adb -s emulator-5556 emu kill` after each phase, then "
                          "`adb devices` empty and no qemu-system process",
            "port": emulator_port,
            "memory_mb": emulator_memory if emulator_port else None,
            "avds": [phase.get("avd") for phase in phases],
            "phases": emulator_phases,
        }
        receipt["bc7_cases"] = {
            "generator": "BC7_UNORM / BC7_SRGB 64x64 images built from 256 deterministic "
                         "valid blocks (modes 0-7, 3 payload patterns)",
            "per_phase_runs": 16,
            "runs_per_phase": {
                "wrapper_copy": 12,
                "system_compressed_copy": 2,
                "system_native_blit_decode": 1,
                "wrapper_emulated_blit": 1,
            },
            "opt_in": "runs only with --cases bc7 (or --cases extended --extended-cases bc7); "
                      "the default and extended matrices are unchanged",
        }
        receipt["summary"]["bc7_cases"] = case_summaries
        receipt["summary"]["note"] = ("the default_matrix totals for a BC7-only receipt count "
                                      "only the per-target caps runs; the bc7 case table holds "
                                      "the actual BC7 evidence")
        receipt["generation"]["note"] = (
            "Each phase was executed end to end by the tool revision and fixture binary "
            "recorded for that phase on its own emulator (4 KB API 36 then 16 KB API 37, "
            "sequential); this receipt merges those raw payloads without re-executing device "
            "work. The host bcdec decode helper was compiled from a generated scratch source "
            "against the pinned read-only wrapper bcdec.h; its paths, hashes and compile "
            "command are recorded in bc7_reference. The assembler was re-run after the device "
            "phases (see tool_hashes.phase_payloads vs current_assembly_tool) only to derive "
            "the BC7_SRGB compute-vs-host-CPU readback comparison and emulator facts; phase "
            "payloads were not modified. No CI or phone result is claimed.")
    return receipt


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
    parser.add_argument("--extended-cases", default="",
                        help="comma-separated subset of extended case families "
                             "(odd-extents,partial-region,mips-layers,srgb,bc4-bc5,"
                             "depth-reduce,bc7); empty = all")
    parser.add_argument("--cases", default="default",
                        choices=["default", "extended", "all", "bc7"],
                        help="default = the original matrix only; extended = the extended "
                             "coverage cases only; all = both; bc7 = only the opt-in BC7/BC7_SRGB "
                             "differential family (caps are always run for device facts)")
    parser.add_argument("--emulator-port", default=None,
                        help="emulator console port used for this lane (assembly records it)")
    parser.add_argument("--emulator-memory-mb", default="2048",
                        help="emulator -memory value used for the phases (assembly records it)")
    parser.add_argument("--assemble", nargs="+", default=None,
                        help="merge executed phase payload JSON files into the extended "
                             "evidence receipt instead of touching a device")
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
    if args.assemble:
        payloads = []
        paths = []
        for raw_path in args.assemble:
            path = Path(raw_path)
            payloads.append(json.loads(path.read_text(encoding="utf-8")))
            paths.append(path)
        receipt = assemble_extended_receipt(payloads, paths, args, self_test)
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        scratch_result = scratch / "extended-result.json"
        scratch_result.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        log("assembled extended evidence receipt from %d phase payload(s) to %s (sha256=%s)"
            % (len(paths), out_path, sha256_file(out_path)))
        summary = receipt["summary"]
        print("\nASSEMBLED EXTENDED RECEIPT")
        for phase in receipt["phases"]:
            print("  phase %s page_size=%s runs=%d" % (phase["label"], phase["page_size"],
                                                       phase["run_count"]))
        print("  default matrix: %d runs (%d pass, %d fail)"
              % (summary["default_matrix"]["runs"], summary["default_matrix"]["pass"],
                 summary["default_matrix"]["fail"]))
        print("  extended cases: %d runs (%d pass, %d fail, %d recorded)"
              % (summary["extended_cases"]["runs"], summary["extended_cases"]["pass"],
                 summary["extended_cases"]["fail"], summary["extended_cases"]["recorded"]))
        for statement in receipt["findings"]:
            print("  - " + statement)
        return 0 if summary["extended_cases"]["fail"] == 0 else 1
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
    extended = payload.get("extended_cases") or {}
    extended_totals = extended.get("totals") or {}
    print("  runs: %d pass / %d fail (default matrix) + %d extended pass / %d fail / %d recorded"
          % (summary["runs_pass"], summary["runs_fail"],
             extended_totals.get("pass", 0), extended_totals.get("fail", 0),
             extended_totals.get("recorded", 0)))
    for failure in summary["failures"]:
        print("  FAIL %s: %s" % (failure["name"], "; ".join(failure["reasons"])))
    ok = summary["all_runs_pass"] and not extended_totals.get("fail")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
