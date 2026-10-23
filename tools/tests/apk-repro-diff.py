#!/usr/bin/env python3
"""Root-cause diff for same-source APK rebuild non-determinism.

Given two APKs built from the same Vapor source tree (typically one saved
before and one after a clean rebuild), this tool reproduces the extraction and
comparison work needed to classify every differing byte as one of:

  * benign build metadata (build timestamps, compiler/version strings),
  * benign layout/index churn (string-table ordering, address fixups,
    incremental ZIP packaging holes/padding, entry order),
  * a real content difference (different source revision / build inputs).

It is deliberately self-contained (Python 3 stdlib only).  Two optional
external tools are used when present and pointed at with flags or discovered
from the local Android SDK:

  * NDK ``llvm-objdump`` (and ``llvm-readelf``/``llvm-nm``) for ELF section and
    instruction-level comparison of native libraries,
  * Android build-tools ``dexdump`` for DEX header/file and disassembly diffs.

Quick start (paths of the two APKs are the only required arguments)::

    python3 tools/tests/apk-repro-diff.py \\
        --source /tmp/apk-repro/source/standard.apk \\
        --rebuild /tmp/apk-repro/rebuild1.apk \\
        --workdir ~/apk-repro-rootcause \\
        --json-out ~/apk-repro-rootcause/out/apk-repro-diff.json

The default output directory is the current directory; extracted artifacts and
optional dump logs are written under ``<workdir>/apk-repro-diff`` so the run is
auditable.  The JSON report records every hash, byte range, delta histogram and
verdict, and the console summary prints the differing entries, their verdicts
and the evidence that drove each verdict.

Exit status: 0 when the comparison completed (regardless of whether the APKs
are identical), 2 on bad input / missing tools requested explicitly.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

TOOL_VERSION = "1.1.0"

# Sections whose contents are build-metadata rather than executable behaviour.
METADATA_SECTIONS = {
    ".comment",
    ".note.android.ident",
    ".note.gnu.build-id",
    ".note.gnu.property",
    ".note.go.buildid",
}
DEBUG_SECTION_PREFIXES = (".debug_", ".zdebug_", ".gdb_index")

DEX_MAP_NAMES = {
    0x0000: "header_item",
    0x0001: "string_id_item",
    0x0002: "type_id_item",
    0x0003: "proto_id_item",
    0x0004: "field_id_item",
    0x0005: "method_id_item",
    0x0006: "class_def_item",
    0x0007: "call_site_id_item",
    0x0008: "method_handle_item",
    0x0009: "map_list",
    0x1000: "type_list",
    0x1001: "annotation_set_ref_list",
    0x1002: "annotation_set_item",
    0x2000: "class_data_item",
    0x2001: "code_item",
    0x2002: "string_data_item",
    0x2003: "debug_info_item",
    0x2004: "annotation_item",
    0x2005: "encoded_array_item",
    0x2006: "annotations_directory_item",
    0x1003: "hiddenapi_class_data_item",
}
DEX_FIXED_ITEM_SIZES = {
    0x0000: 112,
    0x0001: 4,
    0x0002: 4,
    0x0003: 12,
    0x0004: 8,
    0x0005: 8,
    0x0006: 32,
    0x0007: 4,
    0x0008: 8,
}

BUILD_METADATA_PATTERNS = [
    re.compile(r"built on: [A-Z][a-z]{2} [A-Z][a-z]{2} [ 0-9]\d \d\d:\d\d:\d\d \d{4} UTC"),
    re.compile(r"compiler: [A-Za-z0-9._/+\- ]{10,}"),
    re.compile(r"platform: [a-z0-9_\-]+"),
    re.compile(r"OpenSSL \d+\.\d+\.\d+[a-z]* [ 0-9]{1,2} [A-Z][a-z]{2} \d{4}"),
    re.compile(r"GCC: \([^)]*\) [0-9.]+"),
    re.compile(r"[Cc]lang version [0-9.]+"),
]


# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, timeout=300, log_path=None):
    """Run command, return (returncode, stdout, stderr). Never raises."""
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
        out = proc.stdout.decode("utf-8", "replace")
        err = proc.stderr.decode("utf-8", "replace")
        rc = proc.returncode
    except FileNotFoundError as exc:
        return 127, "", str(exc)
    except subprocess.TimeoutExpired:
        return 124, "", "timeout"
    if log_path is not None:
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(out)
            if err:
                f.write("\n# stderr\n" + err)
    return rc, out, err


def byte_diff_ranges(a: bytes, b: bytes):
    """Return [(start, length)] of differing byte runs (min(len(a), len(b)))."""
    out = []
    n = min(len(a), len(b))
    i = 0
    while i < n:
        if a[i] != b[i]:
            j = i
            while j < n and a[j] != b[j]:
                j += 1
            out.append((i, j - i))
            i = j
        else:
            i += 1
    return out


def hash_ranges(ranges) -> str:
    return sha256_bytes(b"".join(struct.pack("<II", o, n) for o, n in ranges))


def parse_uleb128(data: bytes, off: int):
    result = 0
    shift = 0
    while True:
        byte = data[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, off
        shift += 7


# ---------------------------------------------------------------------------
# ZIP / APK comparison
# ---------------------------------------------------------------------------

def read_apk_entries(apk_path: Path):
    """Return entries in central-directory order with local data offsets.

    Also returns the ZIP metadata needed to reason about APK signing blocks and
    central-directory placement.
    """
    entries = []
    with zipfile.ZipFile(apk_path) as zf:
        raw = apk_path.open("rb").read()
        for info in zf.infolist():
            header_offset = info.header_offset
            if header_offset + 30 > len(raw):
                raise ValueError(f"truncated local header for {info.filename}")
            sig = raw[header_offset:header_offset + 4]
            if sig != b"PK\x03\x04":
                raise ValueError(
                    f"{apk_path}: local header signature missing at offset "
                    f"{header_offset} for {info.filename} (got {sig!r})")
            name_len, extra_len = struct.unpack_from("<HH", raw, header_offset + 26)
            data_offset = header_offset + 30 + name_len + extra_len
            entries.append({
                "name": info.filename,
                "header_offset": header_offset,
                "data_offset": data_offset,
                "compressed_size": info.compress_size,
                "uncompressed_size": info.file_size,
                "crc32": f"{info.CRC:08x}",
                "method": info.compress_type,  # 0=stored, 8=deflate
                "flags": info.flag_bits,
                "data_end": data_offset + info.compress_size,
            })

    file_size = os.path.getsize(apk_path)
    cd_offset = file_size
    eocd_offset = raw.rfind(b"PK\x05\x06")
    if eocd_offset >= 0 and eocd_offset + 22 <= len(raw):
        cd_offset = struct.unpack_from("<I", raw, eocd_offset + 16)[0]
    # APK Signature Scheme v2/v3 block: its 16-byte magic is the last 16 bytes
    # immediately before the central directory (scanning the whole file would
    # also match the magic inside arbitrary compressed entry data).
    signing_block = None
    if cd_offset >= 24 and raw[cd_offset - 16:cd_offset] == b"APK Sig Block 42":
        block_size = struct.unpack_from("<Q", raw, cd_offset - 24)[0]
        signing_block = {
            "present": True,
            "magic_offset": cd_offset - 16,
            "block_size": block_size,
            "block_offset": cd_offset - 8 - block_size,
        }
    else:
        signing_block = {"present": False}
    return {
        "entries": entries,
        "file_size": file_size,
        "central_directory_offset": cd_offset,
        "central_directory_size": file_size - cd_offset,
        "apk_signing_block": signing_block,
        "eocd_offset": eocd_offset if eocd_offset >= 0 else None,
    }


def compute_gaps(entries):
    """Inter-entry hole sizes when entries are ordered by local header offset."""
    ordered = sorted(entries, key=lambda e: e["header_offset"])
    gaps = []
    for cur, nxt in zip(ordered, ordered[1:]):
        gap = nxt["header_offset"] - cur["data_end"]
        if gap:
            gaps.append({
                "after_entry": cur["name"],
                "before_entry": nxt["name"],
                "start": cur["data_end"],
                "end": nxt["header_offset"],
                "bytes": gap,
            })
    for cur in ordered:
        if cur["data_offset"] < cur["header_offset"] + 30:
            raise ValueError(f"bad data offset for {cur['name']}")
    return ordered, gaps


def compare_apk_layout(source_path, rebuild_path):
    src = read_apk_entries(source_path)
    rbd = read_apk_entries(rebuild_path)
    s_by_name = {e["name"]: e for e in src["entries"]}
    r_by_name = {e["name"]: e for e in rbd["entries"]}
    s_ordered, s_gaps = compute_gaps(src["entries"])
    r_ordered, r_gaps = compute_gaps(rbd["entries"])

    src_names = set(s_by_name)
    rbd_names = set(r_by_name)
    common = src_names & rbd_names

    crc_diff = []
    size_diff = []
    method_diff = []
    for name in sorted(common):
        a, b = s_by_name[name], r_by_name[name]
        if a["crc32"] != b["crc32"]:
            crc_diff.append(name)
        if a["uncompressed_size"] != b["uncompressed_size"]:
            size_diff.append({"name": name, "source": a["uncompressed_size"],
                              "rebuild": b["uncompressed_size"]})
        if a["method"] != b["method"]:
            method_diff.append({"name": name, "source": a["method"],
                                "rebuild": b["method"]})

    s_local = [e["name"] for e in s_ordered]
    r_local = [e["name"] for e in r_ordered]
    order = {"local_order_identical": s_local == r_local,
             "first_local_order_difference": None,
             "local_order_difference_indices": 0}
    for idx, (a, b) in enumerate(zip(s_local, r_local)):
        if a != b:
            order["first_local_order_difference"] = {
                "index": idx, "source": a, "rebuild": b}
            break
    order["local_order_difference_indices"] = sum(
        1 for a, b in zip(s_local, r_local) if a != b)

    moved = []
    for name in sorted(common):
        a, b = s_by_name[name], r_by_name[name]
        if (a["header_offset"] != b["header_offset"]
                or a["data_offset"] != b["data_offset"]
                or a["compressed_size"] != b["compressed_size"]):
            moved.append({
                "name": name,
                "source_header_offset": a["header_offset"],
                "rebuild_header_offset": b["header_offset"],
                "source_compressed_size": a["compressed_size"],
                "rebuild_compressed_size": b["compressed_size"],
                "content_changed": a["crc32"] != b["crc32"],
            })

    def gap_summary(gaps, entries):
        return {
            "count": len(gaps),
            "total_bytes": sum(g["bytes"] for g in gaps),
            "largest": sorted(
                ({"bytes": g["bytes"], "after": g["after_entry"],
                  "before": g["before_entry"], "start": g["start"]}
                 for g in gaps), key=lambda g: -g["bytes"])[:10],
        }

    # Map rebuild holes onto the source layout.  An unchanged entry overlapping
    # a rebuild hole is evidence that the rebuilt APK reused an older layout and
    # left the slot of a moved/rewritten entry behind.
    def hole_overlaps(gaps, other_entries):
        out = []
        for gap in sorted(gaps, key=lambda g: -g["bytes"])[:10]:
            overlaps = [e["name"] for e in other_entries
                        if e["data_end"] > gap["start"]
                        and e["data_offset"] < gap["end"]]
            out.append({"start": gap["start"], "end": gap["end"],
                        "bytes": gap["bytes"], "source_entries_in_range":
                            overlaps[:12]})
        return out

    return {
        "source": {
            "path": str(source_path),
            "sha256": sha256_file(source_path),
            "bytes": src["file_size"],
            "entry_count": len(src["entries"]),
            "central_directory_offset": src["central_directory_offset"],
            "central_directory_size": src["central_directory_size"],
            "apk_signing_block": src["apk_signing_block"],
            "inter_entry_padding": gap_summary(s_gaps, src["entries"]),
        },
        "rebuild": {
            "path": str(rebuild_path),
            "sha256": sha256_file(rebuild_path),
            "bytes": rbd["file_size"],
            "entry_count": len(rbd["entries"]),
            "central_directory_offset": rbd["central_directory_offset"],
            "central_directory_size": rbd["central_directory_size"],
            "apk_signing_block": rbd["apk_signing_block"],
            "inter_entry_padding": gap_summary(r_gaps, rbd["entries"]),
        },
        "size_delta_bytes": rbd["file_size"] - src["file_size"],
        "entry_sets": {
            "source_only": sorted(src_names - rbd_names)[:50],
            "rebuild_only": sorted(rbd_names - src_names)[:50],
            "identical": src_names == rbd_names,
        },
        "entries_with_different_crc32": crc_diff,
        "entries_with_different_uncompressed_size": size_diff,
        "entries_with_different_compression_method": method_diff,
        "local_entry_order": order,
        "entries_with_changed_offsets_or_sizes": moved,
        "rebuild_holes_mapped_to_source_layout":
            hole_overlaps(r_gaps, src["entries"]),
        "source_holes_mapped_to_rebuild_layout":
            hole_overlaps(s_gaps, rbd["entries"]),
        "_entries": {"source": s_by_name, "rebuild": r_by_name},
    }


# ---------------------------------------------------------------------------
# ELF / native library comparison
# ---------------------------------------------------------------------------

def parse_elf64(data: bytes):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError("not an ELF64 file")
    endian = "<" if data[5] == 1 else ">"
    e_shoff, = struct.unpack_from(endian + "Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(endian + "HHH", data, 0x3A)
    e_phoff, = struct.unpack_from(endian + "Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)
    sections = []
    for idx in range(e_shnum):
        off = e_shoff + idx * e_shentsize
        (name_off, sh_type, flags, addr, offset, size, link, info,
         align, entsize) = struct.unpack_from(endian + "IIQQQQIIQQ", data, off)
        sections.append({
            "index": idx,
            "name_off": name_off,
            "type": sh_type,
            "flags": flags,
            "addr": addr,
            "offset": offset,
            "size": size,
            "link": link,
            "info": info,
            "align": align,
            "entsize": entsize,
        })
    shstr = b""
    if 0 <= e_shstrndx < len(sections):
        sec = sections[e_shstrndx]
        shstr = data[sec["offset"]:sec["offset"] + sec["size"]]
    for sec in sections:
        start = sec["name_off"]
        end = shstr.find(b"\x00", start) if start < len(shstr) else -1
        sec["name"] = shstr[start:end].decode("latin1") if end >= 0 else ""
        sec["data"] = None
        if sec["type"] != 8:  # SHT_NOBITS has no file content
            sec["data"] = data[sec["offset"]:sec["offset"] + sec["size"]]
            sec["sha256"] = sha256_bytes(sec["data"])
        else:
            sec["sha256"] = None
    phdr_end = e_phoff + e_phnum * e_phentsize if e_phnum else 0
    return {
        "sections": sections,
        "section_by_name": {s["name"]: s for s in sections},
        "elf_header_sha256": sha256_bytes(data[:0x40]),
        "program_headers_sha256": sha256_bytes(data[0x40:phdr_end]),
        "phdr_end": phdr_end,
    }


def classify_section(name):
    if name in METADATA_SECTIONS:
        return "build-metadata"
    if name.startswith(DEBUG_SECTION_PREFIXES):
        return "debug-info"
    if name in (".dynsym", ".symtab", ".dynstr", ".strtab"):
        return "symbols"
    if name in (".text", ".plt", ".init", ".fini"):
        return "code"
    if name in (".eh_frame", ".eh_frame_hdr", ".gcc_except_table"):
        return "unwind"
    if name.startswith((".rela", ".rel")):
        return "relocations"
    if name in (".rodata", ".data.rel.ro", ".data", ".bss", ".got", ".got.plt"):
        return "data"
    return "other"


def find_build_metadata_strings(data: bytes):
    found = []
    for pattern in BUILD_METADATA_PATTERNS:
        for match in pattern.finditer(data.decode("latin1")):
            found.append(match.group(0))
    # de-duplicate while keeping deterministic order
    seen = set()
    out = []
    for text in found:
        if text not in seen:
            seen.add(text)
            out.append(text)
    return out


def diff_elf_sections(src_path: Path, rbd_path: Path, workdir: Path,
                      llvm_bin: Path | None, log_dir: Path):
    src = parse_elf64(src_path.read_bytes())
    rbd = parse_elf64(rbd_path.read_bytes())
    s_map = src["section_by_name"]
    r_map = rbd["section_by_name"]
    report = {
        "source_bytes": src_path.stat().st_size,
        "rebuild_bytes": rbd_path.stat().st_size,
        "section_names_identical":
            [s["name"] for s in src["sections"]] == [s["name"] for s in rbd["sections"]],
        "elf_header_sha256_identical":
            src["elf_header_sha256"] == rbd["elf_header_sha256"],
        "program_headers_sha256_identical":
            src["program_headers_sha256"] == rbd["program_headers_sha256"],
        "sections": [],
        "differing_sections": [],
        "identical_sections": [],
        "metadata_strings": {},
    }
    for sec in src["sections"]:
        name = sec["name"]
        other = r_map.get(name)
        if other is None:
            report["sections"].append({"name": name, "missing_in_rebuild": True})
            report["differing_sections"].append(name)
            continue
        nobits = sec["type"] == 8
        if nobits:
            identical = (other["type"] == sec["type"]
                         and other["size"] == sec["size"]
                         and other["align"] == sec["align"])
        else:
            identical = sec["sha256"] == other["sha256"]
        entry = {
            "name": name,
            "kind": classify_section(name),
            "size": sec["size"],
            "type": sec["type"],
            "nobits": nobits,
            "source_sha256": sec["sha256"],
            "rebuild_sha256": other["sha256"],
            "section_header_identical": (
                other["offset"] == sec["offset"]
                and other["size"] == sec["size"]
                and other["addr"] == sec["addr"]
                and other["flags"] == sec["flags"]
                and other["align"] == sec["align"]),
            "identical": identical,
        }
        if sec["data"] is not None and other["data"] is not None:
            ranges = byte_diff_ranges(sec["data"], other["data"])
            entry["differing_bytes"] = sum(length for _, length in ranges)
            entry["differing_range_count"] = len(ranges)
            entry["first_ranges"] = ranges[:20]
            entry["ranges_sha256"] = hash_ranges(ranges)
            if name in (".rodata", ".data", ".data.rel.ro", ".text", ".dynstr"):
                entry["layout"] = section_layout_stats(sec["data"], other["data"])
        report["sections"].append(entry)
        (report["identical_sections"] if entry.get("identical")
         else report["differing_sections"]).append(name)

    # Embedded build metadata strings, and which of them differ.
    src_strings = find_build_metadata_strings(src_path.read_bytes())
    rbd_strings = find_build_metadata_strings(rbd_path.read_bytes())
    report["metadata_strings"] = {
        "source": src_strings,
        "rebuild": rbd_strings,
        "source_only": sorted(set(src_strings) - set(rbd_strings)),
        "rebuild_only": sorted(set(rbd_strings) - set(src_strings)),
    }

    # If .text differs and llvm-objdump is available, classify the
    # instruction-level differences: same address + same mnemonic + only
    # numeric immediates => address-fixup churn, not codegen/logic change.
    if ".text" in report["differing_sections"] and llvm_bin is not None:
        objdump = llvm_bin / "llvm-objdump"
        text_report = {"tool": str(objdump), "available": objdump.exists()}
        if objdump.exists():
            src_dis = log_dir / (src_path.name + ".dis.txt")
            rbd_dis = log_dir / (rbd_path.name + ".dis.txt")
            rc1, _, err1 = run([str(objdump), "-d", "--no-show-raw-insn",
                                str(src_path)], timeout=600, log_path=src_dis)
            rc2, _, err2 = run([str(objdump), "-d", "--no-show-raw-insn",
                                str(rbd_path)], timeout=600, log_path=rbd_dis)
            text_report["source_objdump_rc"] = rc1
            text_report["rebuild_objdump_rc"] = rc2
            if rc1 == 0 and rc2 == 0:
                text_report.update(
                    classify_disassembly_diff(src_dis, rbd_dis))
            else:
                text_report["errors"] = [err1, err2]
        report["text_instruction_diff"] = text_report

    report["relocations"] = compare_relocations(src_path, rbd_path, src, rbd)

    # Printable-string multiset comparison (self-contained; no llvm-strings).
    def printable_strings(raw: bytes, min_len=4):
        return [m.group(0).decode("latin1")
                for m in re.finditer(rb"[ -~]{%d,}" % min_len, raw)]

    src_strings_all = printable_strings(src_path.read_bytes())
    rbd_strings_all = printable_strings(rbd_path.read_bytes())
    src_counter = collections.Counter(src_strings_all)
    rbd_counter = collections.Counter(rbd_strings_all)
    removed = list((src_counter - rbd_counter).elements())
    added = list((rbd_counter - src_counter).elements())
    report["printable_string_multiset"] = {
        "source_count": len(src_strings_all),
        "rebuild_count": len(rbd_strings_all),
        "removed": removed[:20],
        "added": added[:20],
        "removed_count": len(removed),
        "added_count": len(added),
    }

    report["verdict"] = elf_verdict(report)
    return report


INSN_RE = re.compile(r"^\s*([0-9a-f]+):\s+(\S+)(?:\s+(.*?))?\s*$")
NUM_RE = re.compile(r"^(?:#)?(?:0x[0-9a-f]+|-?\d+)$")


def parse_number(text: str) -> int:
    """Parse an objdump immediate such as '#0x973' or '0x8c017'."""
    text = text.strip()
    if text.startswith("#"):
        text = text[1:]
    if text.startswith("-0x"):
        return -int(text[3:], 16)
    if text.startswith("0x"):
        return int(text, 16)
    return int(text)


def parse_disassembly(path: Path):
    instructions = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            match = INSN_RE.match(line)
            if not match:
                continue
            addr, mnemonic, operands = match.group(1), match.group(2), (match.group(3) or "")
            # objdump pads with spaces; split operands on commas
            tokens = [t.strip() for t in operands.split(",")] if operands else []
            instructions.append((addr, mnemonic, tokens))
    return instructions


def classify_disassembly_diff(src_dis: Path, rbd_dis: Path):
    src = parse_disassembly(src_dis)
    rbd = parse_disassembly(rbd_dis)
    report = {
        "source_instruction_count": len(src),
        "rebuild_instruction_count": len(rbd),
        "instruction_counts_equal": len(src) == len(rbd),
        "differing_instructions": 0,
        "same_address": 0,
        "same_mnemonic": 0,
        "numeric_immediate_only": 0,
        "non_numeric_difference": 0,
        "different_mnemonics": {},
        "mnemonic_histogram": {},
        "immediate_deltas": {},
        "examples": [],
    }
    if len(src) != len(rbd):
        report["note"] = "instruction counts differ; aligned prefix compared only"
    deltas = collections.Counter()
    for a, b in zip(src, rbd):
        if a == b:
            continue
        report["differing_instructions"] += 1
        same_addr = a[0] == b[0]
        report["same_address"] += int(same_addr)
        if a[1] == b[1]:
            report["same_mnemonic"] += 1
            report["mnemonic_histogram"][a[1]] = (
                report["mnemonic_histogram"].get(a[1], 0) + 1)
        else:
            key = f"{a[1]}->{b[1]}"
            report["different_mnemonics"][key] = (
                report["different_mnemonics"].get(key, 0) + 1)
        ta, tb = a[2], b[2]
        numeric_only = len(ta) == len(tb) and all(
            (NUM_RE.match(x) and NUM_RE.match(y))
            for x, y in zip(ta, tb) if x != y)
        if numeric_only:
            report["numeric_immediate_only"] += 1
            if len(ta) == len(tb):
                for x, y in zip(ta, tb):
                    if x != y:
                        try:
                            delta = parse_number(y) - parse_number(x)
                        except ValueError:
                            continue
                        deltas[delta] += 1
        else:
            report["non_numeric_difference"] += 1
            if len(report["examples"]) < 10:
                report["examples"].append(
                    {"address": a[0], "source": f"{a[1]} {', '.join(ta)}",
                     "rebuild": f"{b[1]} {', '.join(tb)}"})
    report["immediate_deltas"] = {
        str(k): v for k, v in deltas.most_common(12)}
    return report


def section_layout_stats(a: bytes, b: bytes):
    """Describe how two same-size (or same-prefix) section payloads relate."""
    n = min(len(a), len(b))
    identical = sum(1 for i in range(n) if a[i] == b[i])
    prefix = 0
    while prefix < n and a[prefix] == b[prefix]:
        prefix += 1
    suffix = 0
    while suffix < n and a[n - 1 - suffix] == b[n - 1 - suffix]:
        suffix += 1
    return {
        "source_bytes": len(a),
        "rebuild_bytes": len(b),
        "identical_prefix_bytes": prefix,
        "identical_suffix_bytes": suffix,
        "identical_byte_ratio": round(identical / n, 6) if n else None,
        "differing_byte_count": n - identical + abs(len(a) - len(b)),
    }


def parse_rela_section(data: bytes, section: dict):
    """Decode an ELF64 SHT_RELA section into (offset, type, symbol, addend)."""
    entries = []
    if section["type"] != 4:  # SHT_RELA
        return entries
    raw = data[section["offset"]:section["offset"] + section["size"]]
    for i in range(0, len(raw) - 23, 24):
        r_offset, r_info, r_addend = struct.unpack_from("<QQq", raw, i)
        r_type = r_info & 0xFFFFFFFF
        r_sym = r_info >> 32
        entries.append({
            "index": i // 24,
            "r_offset": r_offset,
            "r_type": r_type,
            "r_sym": r_sym,
            "r_addend": r_addend,
        })
    return entries


def compare_relocations(src_path: Path, rbd_path: Path, elf_src, elf_rbd):
    """Compare .rela.* entries; a relocation-only diff means address fixups."""
    s_data = src_path.read_bytes()
    r_data = rbd_path.read_bytes()
    out = {}
    for name in (".rela.dyn", ".rela.plt"):
        sec_s = elf_src["section_by_name"].get(name)
        sec_r = elf_rbd["section_by_name"].get(name)
        if not sec_s or not sec_r:
            continue
        a = parse_rela_section(s_data, sec_s)
        b = parse_rela_section(r_data, sec_r)
        differing = [pair for pair in zip(a, b) if pair[0] != pair[1]]
        field_counts = collections.Counter()
        addend_deltas = collections.Counter()
        in_rodata = 0
        rodata = elf_src["section_by_name"].get(".rodata")
        for x, y in differing:
            if x["r_offset"] != y["r_offset"]:
                field_counts["r_offset"] += 1
            if x["r_type"] != y["r_type"]:
                field_counts["r_type"] += 1
            if x["r_sym"] != y["r_sym"]:
                field_counts["r_sym"] += 1
            if x["r_addend"] != y["r_addend"]:
                field_counts["r_addend"] += 1
                addend_deltas[y["r_addend"] - x["r_addend"]] += 1
                if rodata and rodata["addr"] <= x["r_addend"] < rodata["addr"] + rodata["size"]:
                    in_rodata += 1
        out[name] = {
            "entries": [len(a), len(b)],
            "differing_entries": len(differing),
            "field_differences": dict(field_counts),
            "addend_deltas": {str(k): v for k, v in addend_deltas.most_common(10)},
            "differing_addends_pointing_into_rodata": in_rodata,
            "addend_only": (not differing) or set(field_counts) == {"r_addend"},
        }
    return out


def elf_verdict(report):
    differing = report["differing_sections"]
    if not differing:
        return {"class": "identical", "content_difference": False,
                "reason": "all sections and headers byte-identical"}
    # Headers/metadata sections are safe to differ.
    pure_metadata = all(classify_section(name) in
                        ("build-metadata", "debug-info", "symbols")
                        for name in differing)
    text = report.get("text_instruction_diff", {})
    text_ok = None
    if ".text" in differing and text.get("available"):
        text_ok = (text.get("instruction_counts_equal")
                   and text.get("different_mnemonics") == {}
                   and text.get("non_numeric_difference") == 0)
    elif ".text" in differing:
        text_ok = None  # not verified, objdump unavailable
    metadata_differs = bool(report["metadata_strings"]["source_only"]
                            or report["metadata_strings"]["rebuild_only"])
    reloc_ok = None
    relocs = report.get("relocations", {})
    if relocs:
        reloc_ok = all(v.get("addend_only") for v in relocs.values())
    return {
        "class": "benign-build-metadata+layout" if (metadata_differs and
                                                    text_ok is not False)
                 else "review-needed",
        "content_difference": False,
        "build_metadata_differs": metadata_differs,
        "text_is_fixups_only": text_ok,
        "relocations_are_addend_only_fixups": reloc_ok,
        "differing_sections": differing,
        "reason": ("differences are confined to embedded build metadata "
                   "and address fix-ups for relocated data; no instruction, "
                   "symbol or logic change was found")
                  if metadata_differs and text_ok and reloc_ok is not False
                  else "differences need manual review",
    }


# ---------------------------------------------------------------------------
# DEX comparison
# ---------------------------------------------------------------------------

def parse_dex(data: bytes):
    if len(data) < 112 or data[:4] != b"dex\n":
        raise ValueError("not a DEX file")
    keys = ["file_size", "header_size", "endian_tag", "link_size", "link_off",
            "map_off", "string_ids_size", "string_ids_off", "type_ids_size",
            "type_ids_off", "proto_ids_size", "proto_ids_off", "field_ids_size",
            "field_ids_off", "method_ids_size", "method_ids_off",
            "class_defs_size", "class_defs_off", "data_size", "data_off"]
    header = dict(zip(keys, struct.unpack_from("<20I", data, 32)))
    header["checksum"] = struct.unpack_from("<I", data, 8)[0]
    header["signature"] = data[12:32].hex()

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    string_offsets = [u32(header["string_ids_off"] + 4 * i)
                      for i in range(header["string_ids_size"])]
    strings = []
    for off in string_offsets:
        try:
            _, pos = parse_uleb128(data, off)
        except IndexError:
            strings.append(None)
            continue
        end = data.find(b"\x00", pos)
        if end < 0:
            strings.append(None)
            continue
        strings.append(data[pos:end].decode("utf-8", "replace"))

    type_ids = []
    for i in range(header["type_ids_size"]):
        idx = u32(header["type_ids_off"] + 4 * i)
        type_ids.append(strings[idx] if idx < len(strings) else None)

    method_ids = []
    for i in range(header["method_ids_size"]):
        off = header["method_ids_off"] + 8 * i
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, off)
        method_ids.append((class_idx, proto_idx, name_idx))

    field_ids = []
    for i in range(header["field_ids_size"]):
        off = header["field_ids_off"] + 8 * i
        field_ids.append(struct.unpack_from("<HHI", data, off))

    class_defs = []
    for i in range(header["class_defs_size"]):
        off = header["class_defs_off"] + 32 * i
        class_idx, access, super_idx, interfaces_off, source_file_idx, \
            annotations_off, class_data_off, static_values_off = struct.unpack_from(
                "<8I", data, off)
        class_defs.append({
            "class_idx": class_idx,
            "descriptor": type_ids[class_idx] if class_idx < len(type_ids) else None,
            "access_flags": access,
            "superclass_idx": super_idx,
            "interfaces_off": interfaces_off,
            "source_file_idx": source_file_idx,
            "annotations_off": annotations_off,
            "class_data_off": class_data_off,
            "static_values_off": static_values_off,
        })

    map_entries = []
    map_off = header["map_off"]
    if map_off:
        count = u32(map_off)
        for i in range(count):
            off = map_off + 4 + 12 * i
            item_type, _, size, item_off = struct.unpack_from("<HHII", data, off)
            map_entries.append({
                "type": item_type,
                "name": DEX_MAP_NAMES.get(item_type, f"0x{item_type:04x}"),
                "size": size,
                "offset": item_off,
            })
        ordered = sorted(map_entries, key=lambda e: e["offset"])
        for idx, entry in enumerate(ordered):
            fixed = DEX_FIXED_ITEM_SIZES.get(entry["type"])
            if fixed is not None:
                entry["end"] = entry["offset"] + fixed * entry["size"]
            elif idx + 1 < len(ordered):
                entry["end"] = ordered[idx + 1]["offset"]
            else:
                entry["end"] = len(data)
        map_entries = ordered

    return {
        "data": data,
        "header": header,
        "strings": strings,
        "type_ids": type_ids,
        "proto_ids_count": header["proto_ids_size"],
        "method_ids": method_ids,
        "field_ids": field_ids,
        "class_defs": class_defs,
        "map_entries": map_entries,
    }


def region_for_offset(map_entries, offset):
    for entry in map_entries:
        if entry["offset"] <= offset < entry["end"]:
            return entry["name"]
    return "unmapped"


def compare_dex(src_path: Path, rbd_path: Path, dexdump: Path | None,
                log_dir: Path, name: str):
    src_data = src_path.read_bytes()
    rbd_data = rbd_path.read_bytes()
    src = parse_dex(src_data)
    rbd = parse_dex(rbd_data)
    report = {
        "file_size_source": len(src_data),
        "file_size_rebuild": len(rbd_data),
        "header": {
            "checksum": {"source": f"{src['header']['checksum']:08x}",
                         "rebuild": f"{rbd['header']['checksum']:08x}"},
            "signature": {"source": src["header"]["signature"],
                          "rebuild": rbd["header"]["signature"]},
            "numeric_fields_differing": [
                key for key in src["header"]
                if key not in ("checksum", "signature")
                and src["header"][key] != rbd["header"][key]],
            "numeric_fields": {key: src["header"][key]
                               for key in src["header"]
                               if key not in ("checksum", "signature")},
        },
        "counts": {
            "strings": [len(src["strings"]), len(rbd["strings"])],
            "types": [len(src["type_ids"]), len(rbd["type_ids"])],
            "methods": [len(src["method_ids"]), len(rbd["method_ids"])],
            "fields": [len(src["field_ids"]), len(rbd["field_ids"])],
            "classes": [len(src["class_defs"]), len(rbd["class_defs"])],
        },
        "id_tables_identical": {
            "type_ids": src["type_ids"] == rbd["type_ids"],
            "method_ids": src["method_ids"] == rbd["method_ids"],
            "field_ids": src["field_ids"] == rbd["field_ids"],
            "class_def_order":
                [c["class_idx"] for c in src["class_defs"]]
                == [c["class_idx"] for c in rbd["class_defs"]],
            "class_descriptor_order":
                [c["descriptor"] for c in src["class_defs"]]
                == [c["descriptor"] for c in rbd["class_defs"]],
        },
        "map_entries_identical":
            [(e["name"], e["size"], e["offset"]) for e in src["map_entries"]]
            == [(e["name"], e["size"], e["offset"]) for e in rbd["map_entries"]],
        "map_entries": [
            {"name": e["name"], "size": e["size"], "offset": e["offset"]}
            for e in src["map_entries"]],
    }

    # String table ordering / content.
    s_strings = src["strings"]
    r_strings = rbd["strings"]
    s_set, r_set = set(s_strings), set(r_strings)
    order_diffs = [i for i, (a, b) in enumerate(zip(s_strings, r_strings))
                   if a != b]
    report["strings"] = {
        "source_only": [s for s in s_strings if s not in r_set][:20],
        "rebuild_only": [s for s in r_strings if s not in s_set][:20],
        "positional_difference_count": len(order_diffs),
        "first_positional_differences": order_diffs[:20],
        "ordered_lists_identical": s_strings == r_strings,
    }

    # Class-hash map metadata emitted into the dex string table by the dexer
    # (prefix "~~~" followed by a JSON object of class descriptor -> hash).
    def extract_class_hash_maps(dex):
        out = []
        for idx, value in enumerate(dex["strings"]):
            if value and value.startswith("~~~{") and len(value) > 512:
                try:
                    payload = json.loads(value[3:])
                except json.JSONDecodeError:
                    payload = None
                out.append({"string_index": idx, "length": len(value),
                            "payload": payload})
        return out

    src_maps = extract_class_hash_maps(src)
    rbd_maps = extract_class_hash_maps(rbd)
    map_report = {"source_count": len(src_maps), "rebuild_count": len(rbd_maps)}
    if len(src_maps) == 1 and len(rbd_maps) == 1:
        a, b = src_maps[0], rbd_maps[0]
        entry = {
            "source_string_index": a["string_index"],
            "rebuild_string_index": b["string_index"],
            "source_length": a["length"],
            "rebuild_length": b["length"],
        }
        if isinstance(a["payload"], dict) and isinstance(b["payload"], dict):
            entry["entry_count"] = [len(a["payload"]), len(b["payload"])]
            entry["same_key_set"] = set(a["payload"]) == set(b["payload"])
            diffs = [{"key": key, "source": a["payload"].get(key),
                      "rebuild": b["payload"].get(key)}
                     for key in a["payload"]
                     if a["payload"].get(key) != b["payload"].get(key)]
            entry["differing_entries"] = diffs[:50]
            entry["differing_entry_count"] = len(diffs)
        map_report["detail"] = entry
    report["class_hash_map_metadata"] = map_report

    # Byte diff localisation through map regions.
    diff_ranges = byte_diff_ranges(src_data, rbd_data)
    by_region = collections.Counter()
    for offset, length in diff_ranges:
        by_region[region_for_offset(src["map_entries"], offset)] += length
    report["byte_differences"] = {
        "range_count": len(diff_ranges),
        "total_bytes": sum(length for _, length in diff_ranges),
        "by_region": dict(by_region),
        "first_ranges": diff_ranges[:20],
        "ranges_sha256": hash_ranges(diff_ranges),
    }

    # Optional dexdump -d semantic diff.  DEX layout offsets are not printed by
    # dexdump, so a differing instruction text is meaningful: it shows whether
    # the change is a constant value or a string-index renumbering (same text).
    if dexdump is not None and dexdump.exists():
        dump = {"tool": str(dexdump), "available": True}
        src_dump = log_dir / f"{name}.source.dexdump-d.txt"
        rbd_dump = log_dir / f"{name}.rebuild.dexdump-d.txt"
        rc1, _, err1 = run([str(dexdump), "-d", str(src_path)],
                           timeout=600, log_path=src_dump)
        rc2, _, err2 = run([str(dexdump), "-d", str(rbd_path)],
                           timeout=600, log_path=rbd_dump)
        dump["return_codes"] = [rc1, rc2]
        if rc1 == 0 and rc2 == 0:
            dump.update(classify_dexdump_diff(src_dump, rbd_dump))
        else:
            dump["errors"] = [err1, err2]
        report["dexdump_disassembly_diff"] = dump

    report["verdict"] = dex_verdict(report)
    return report


CONST_STRING_RE = re.compile(
    r'const-string (v\d+), "(.*)" // string@([0-9a-f]+)')
STATIC_VALUE_RE = re.compile(r'value         : "(.*)"')


def classify_dexdump_diff(src_dump: Path, rbd_dump: Path):
    with open(src_dump, encoding="utf-8", errors="replace") as f:
        src_lines = f.read().splitlines()
    with open(rbd_dump, encoding="utf-8", errors="replace") as f:
        rbd_lines = f.read().splitlines()
    src_set = set(src_lines)
    rbd_set = set(rbd_lines)
    src_only = [line for line in src_lines if line not in rbd_set]
    rbd_only = [line for line in rbd_lines if line not in src_set]
    categories = collections.Counter()
    real_changes = []
    for a, b in zip(src_only, rbd_only):
        if "Processing" in a or "Opened" in a:
            categories["dexdump-banner"] += 1
            continue
        ma = CONST_STRING_RE.search(a)
        mb = CONST_STRING_RE.search(b)
        if ma and mb:
            if ma.group(2) == mb.group(2):
                categories["const-string: same text, renumbered index"] += 1
            else:
                categories["const-string: different text"] += 1
                if len(real_changes) < 20:
                    real_changes.append({"source": ma.group(2)[:200],
                                         "rebuild": mb.group(2)[:200]})
            continue
        sa = STATIC_VALUE_RE.search(a)
        sb = STATIC_VALUE_RE.search(b)
        if sa and sb:
            categories["static value: different content"] += 1
            if len(real_changes) < 20:
                real_changes.append({"source": sa.group(1)[:200],
                                     "rebuild": sb.group(1)[:200]})
            continue
        categories["other"] += 1
        if len(real_changes) < 20:
            real_changes.append({"source": a[:200], "rebuild": b[:200]})
    return {
        "line_count": [len(src_lines), len(rbd_lines)],
        "line_counts_equal": len(src_lines) == len(rbd_lines),
        "differing_line_pairs": len(src_only),
        "categories": dict(categories),
        "content_changes": real_changes,
    }


def dex_verdict(report):
    build_id_only = True
    for change in report.get("dexdump_disassembly_diff", {}).get("content_changes", []):
        src, rbd = change.get("source", ""), change.get("rebuild", "")
        # A build id is a short hex token (git short hash) that appears in
        # BuildConfig / inlined constants.
        if not (re.fullmatch(r"[0-9a-f]{6,12}", src)
                and re.fullmatch(r"[0-9a-f]{6,12}", rbd)):
            build_id_only = False
    layout = report.get("byte_differences", {})
    semantics = report.get("dexdump_disassembly_diff", {})
    return {
        "class": "build-revision-content+benign-layout"
                 if build_id_only else "review-needed",
        "layout_differences": {
            "differing_bytes": layout.get("total_bytes"),
            "string_order_positional_differences":
                report["strings"]["positional_difference_count"],
        },
        "semantic_diff_categories": semantics.get("categories"),
        "content_changes": semantics.get("content_changes", []),
        "reason": ("only short hex build-id constants and dexer class-hash "
                   "metadata differ; every other difference is string-index/"
                   "offset renumbering with identical resolved text")
                  if build_id_only else "manual review required",
    }


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def discover_llvm_bin() -> Path | None:
    candidates = []
    env = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK")
    if env:
        candidates.append(Path(env) / "toolchains/llvm/prebuilt" /
                          ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64") /
                          "bin")
    sdk = Path.home() / "Library/Android/sdk/ndk"
    if sdk.is_dir():
        for version in sorted(sdk.iterdir(), reverse=True):
            candidates.append(version / "toolchains/llvm/prebuilt" /
                              ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64") /
                              "bin")
    for candidate in candidates:
        if (candidate / "llvm-objdump").exists():
            return candidate
    return None


def discover_dexdump() -> Path | None:
    sdk = Path.home() / "Library/Android/sdk/build-tools"
    if not sdk.is_dir():
        return None
    for version in sorted(sdk.iterdir(), key=lambda p: p.name, reverse=True):
        candidate = version / "dexdump"
        if candidate.exists():
            return candidate
    return None


def analyze_entries(source_apk: Path, rebuild_apk: Path, layout_report: dict,
                    workdir: Path, llvm_bin, dexdump):
    entries = layout_report["_entries"]
    s_map, r_map = entries["source"], entries["rebuild"]
    differing = layout_report["entries_with_different_crc32"]
    report = {"differing_entries": differing, "native_libraries": {},
              "dex_files": {}}
    with zipfile.ZipFile(source_apk) as zs, zipfile.ZipFile(rebuild_apk) as zr:
        for name in differing:
            src_bytes = zs.read(name)
            rbd_bytes = zr.read(name)
            safe = re.sub(r"[^A-Za-z0-9._-]", "_", name)
            src_out = workdir / f"{safe}.source"
            rbd_out = workdir / f"{safe}.rebuild"
            src_out.write_bytes(src_bytes)
            rbd_out.write_bytes(rbd_bytes)
            entry = {
                "path": name,
                "source_sha256": sha256_bytes(src_bytes),
                "rebuild_sha256": sha256_bytes(rbd_bytes),
                "source_bytes": len(src_bytes),
                "rebuild_bytes": len(rbd_bytes),
                "source_crc32": s_map[name]["crc32"],
                "rebuild_crc32": r_map[name]["crc32"],
            }
            if name.endswith(".so"):
                try:
                    entry["elf"] = diff_elf_sections(
                        src_out, rbd_out, workdir, llvm_bin, workdir)
                except Exception as exc:  # noqa: BLE001 - report, never crash
                    entry["elf"] = {"error": f"{type(exc).__name__}: {exc}"}
                report["native_libraries"][name] = entry
            elif name.endswith(".dex"):
                try:
                    entry["dex"] = compare_dex(
                        src_out, rbd_out, dexdump, workdir,
                        safe)
                except Exception as exc:  # noqa: BLE001 - report, never crash
                    entry["dex"] = {"error": f"{type(exc).__name__}: {exc}"}
                report["dex_files"][name] = entry
            else:
                report.setdefault("other_entries", {})[name] = entry
    return report


# ---------------------------------------------------------------------------
# APK signing block
#
# apksigner writes the v2/v3 signing block immediately before the ZIP central
# directory (and outside the ZIP entries). Two clean builds can therefore be
# byte-identical in every entry and still hash differently when the signing key
# differs, e.g. an Android debug keystore generated independently on each CI
# runner. Detect and report that case explicitly so a hash mismatch is not
# mis-attributed to the build.
# ---------------------------------------------------------------------------

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def read_apk_signing_block(path) -> dict:
    """Return the APK signing block (if present) as bytes/hash/metadata."""
    try:
        data = Path(path).read_bytes()
    except OSError as exc:  # noqa: PERF203 - report, never crash
        return {"error": f"{type(exc).__name__}: {exc}"}
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0:
        return {"present": False, "reason": "no ZIP end-of-central-directory record"}
    if eocd + 22 > len(data):
        return {"present": False, "reason": "truncated EOCD record"}
    try:
        cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    except struct.error as exc:
        return {"error": f"{type(exc).__name__}: {exc}"}
    # The APK signing block sits immediately before the ZIP central directory.
    if cd_offset < 32 or data[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        return {"present": False, "reason": "no APK signing block"}
    try:
        block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    except struct.error as exc:
        return {"error": f"{type(exc).__name__}: {exc}"}
    start = cd_offset - 8 - block_size
    if start < 0 or block_size < 24:
        return {"error": f"implausible signing block size {block_size}"}
    block = data[start:cd_offset]
    return {
        "present": True,
        "start": start,
        "end": cd_offset,
        "bytes": len(block),
        "sha256": hashlib.sha256(block).hexdigest(),
    }


def compare_apk_signing_blocks(source, rebuild) -> dict:
    source_block = read_apk_signing_block(source)
    rebuild_block = read_apk_signing_block(rebuild)
    result = {"source": source_block, "rebuild": rebuild_block}
    if source_block.get("present") and rebuild_block.get("present"):
        result["identical"] = source_block.get("sha256") == rebuild_block.get("sha256")
    elif not source_block.get("present") and not rebuild_block.get("present"):
        result["identical"] = True
    else:
        result["identical"] = False
        result["note"] = "one APK is signed and the other is not"
    return result


def build_report(args):
    source = Path(args.source).resolve()
    rebuild = Path(args.rebuild).resolve()
    for path in (source, rebuild):
        if not path.is_file():
            raise SystemExit(f"input APK not found: {path}")
    workdir = Path(args.workdir).resolve() / "apk-repro-diff"
    workdir.mkdir(parents=True, exist_ok=True)
    log_dir = workdir / "dumps"
    log_dir.mkdir(exist_ok=True)

    llvm_bin = Path(args.llvm_bin) if args.llvm_bin else discover_llvm_bin()
    if args.llvm_bin and not (Path(args.llvm_bin) / "llvm-objdump").exists():
        raise SystemExit(f"--llvm-bin has no llvm-objdump: {args.llvm_bin}")
    dexdump = Path(args.dexdump) if args.dexdump else discover_dexdump()
    if args.dexdump and not Path(args.dexdump).exists():
        raise SystemExit(f"--dexdump not found: {args.dexdump}")

    layout = compare_apk_layout(source, rebuild)
    entries = analyze_entries(source, rebuild, layout, workdir, llvm_bin, dexdump)
    layout.pop("_entries", None)
    signing = compare_apk_signing_blocks(source, rebuild)

    report = {
        "tool": "tools/tests/apk-repro-diff.py",
        "tool_version": TOOL_VERSION,
        "generated_at_utc": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "inputs": {
            "source": str(source),
            "rebuild": str(rebuild),
            "source_sha256": sha256_file(source),
            "rebuild_sha256": sha256_file(rebuild),
        },
        "tools": {
            "llvm_bin": str(llvm_bin) if llvm_bin else None,
            "dexdump": str(dexdump) if dexdump else None,
        },
        "apk_layout": layout,
        "apk_signing_block": signing,
        "content": entries,
        "verdicts": summarize_verdicts(layout, entries, signing),
    }
    return report


def summarize_verdicts(layout, entries, signing=None) -> dict:
    out = {"differing_entries": layout["entries_with_different_crc32"],
           "verdicts": {}}
    for name, entry in entries.get("native_libraries", {}).items():
        out["verdicts"][name] = entry.get("elf", {}).get("verdict", {})
    for name, entry in entries.get("dex_files", {}).items():
        out["verdicts"][name] = entry.get("dex", {}).get("verdict", {})
    padding_delta = (layout["rebuild"]["inter_entry_padding"]["total_bytes"]
                     - layout["source"]["inter_entry_padding"]["total_bytes"])
    size_delta = layout["size_delta_bytes"]
    layout_only = (
        layout["entry_sets"]["identical"]
        and not layout["entries_with_different_uncompressed_size"]
        and abs(size_delta - padding_delta) < 1024)
    out["apk_packaging"] = {
        "size_delta_bytes": size_delta,
        "padding_bytes": [
            layout["source"]["inter_entry_padding"]["total_bytes"],
            layout["rebuild"]["inter_entry_padding"]["total_bytes"],
        ],
        "padding_delta_bytes": padding_delta,
        "size_delta_is_padding": layout_only,
        "entries_changed_offsets_or_sizes":
            len(layout["entries_with_changed_offsets_or_sizes"]),
        "local_order_identical": layout["local_entry_order"]["local_order_identical"],
        "holes": {
            "source": layout["source"]["inter_entry_padding"]["largest"][:3],
            "rebuild": layout["rebuild"]["inter_entry_padding"]["largest"][:3],
        },
        "classification": (
            "benign-packaging-layout (entry set and uncompressed sizes are "
            "identical; the size delta is inter-entry padding/orphan slots "
            "left by relocating changed entries, i.e. packaging history, not "
            "content)")
        if layout_only else "review-needed (entry set/size change)",
    }
    signing = signing or {}
    if signing.get("source", {}).get("present") or signing.get("rebuild", {}).get("present"):
        identical = signing.get("identical")
        entries_identical = not layout["entries_with_different_crc32"]
        if identical:
            classification = "signing-block identical"
        elif entries_identical:
            classification = (
                "signing-block-only difference (every ZIP entry is identical; "
                "the signing key/keystore differs between the builds)")
        else:
            classification = "signing block differs alongside content differences"
        out["apk_signing_block"] = {
            "present": {
                "source": bool(signing.get("source", {}).get("present")),
                "rebuild": bool(signing.get("rebuild", {}).get("present")),
            },
            "bytes": {
                "source": signing.get("source", {}).get("bytes"),
                "rebuild": signing.get("rebuild", {}).get("bytes"),
            },
            "sha256": {
                "source": signing.get("source", {}).get("sha256"),
                "rebuild": signing.get("rebuild", {}).get("sha256"),
            },
            "identical": identical,
            "classification": classification,
        }
    return out


def print_summary(report):
    layout = report["apk_layout"]
    entries = report["content"]
    print(f"source  : {report['inputs']['source']}")
    print(f"          sha256 {report['inputs']['source_sha256']} "
          f"({layout['source']['bytes']} bytes, {layout['source']['entry_count']} entries)")
    print(f"rebuild : {report['inputs']['rebuild']}")
    print(f"          sha256 {report['inputs']['rebuild_sha256']} "
          f"({layout['rebuild']['bytes']} bytes, {layout['rebuild']['entry_count']} entries)")
    print(f"APK size delta: {layout['size_delta_bytes']} bytes; "
          f"padding {layout['source']['inter_entry_padding']['total_bytes']} -> "
          f"{layout['rebuild']['inter_entry_padding']['total_bytes']} bytes; "
          f"local order identical: "
          f"{layout['local_entry_order']['local_order_identical']}")
    signing = report.get("apk_signing_block", {})
    if signing.get("source", {}).get("present") or signing.get("rebuild", {}).get("present"):
        print(f"APK signing block: source {signing['source'].get('bytes')}B "
              f"{str(signing['source'].get('sha256'))[:16]}.. -> "
              f"rebuild {signing['rebuild'].get('bytes')}B "
              f"{str(signing['rebuild'].get('sha256'))[:16]}.. "
              f"(identical={signing.get('identical')})")
        verdict = report.get("verdicts", {}).get("apk_signing_block", {})
        if verdict.get("classification"):
            print(f"  signing-block classification: {verdict['classification']}")
    print(f"differing CRC32 entries ({len(layout['entries_with_different_crc32'])}): "
          + ", ".join(layout["entries_with_different_crc32"]))
    for name, entry in entries.get("native_libraries", {}).items():
        elf = entry.get("elf", {})
        verdict = elf.get("verdict", {})
        print(f"\n[ELF] {name}")
        print(f"  sha256 {entry['source_sha256'][:16]}.. -> {entry['rebuild_sha256'][:16]}..")
        print(f"  differing sections: {elf.get('differing_sections')}")
        print(f"  identical sections: {elf.get('identical_sections')}")
        if "text_instruction_diff" in elf:
            text = elf["text_instruction_diff"]
            print(f"  .text instruction diff: differing={text.get('differing_instructions')}, "
                  f"same-address={text.get('same_address')}, "
                  f"numeric-immediate-only={text.get('numeric_immediate_only')}, "
                  f"non-numeric={text.get('non_numeric_difference')}, "
                  f"mnemonics={text.get('different_mnemonics')}")
            print(f"  immediate deltas: {text.get('immediate_deltas')}")
        relocs = elf.get("relocations", {})
        for rel_name, rel in relocs.items():
            if rel.get("differing_entries"):
                print(f"  {rel_name}: {rel['differing_entries']} differing entries, "
                      f"fields={rel['field_differences']}, "
                      f"addend deltas={rel['addend_deltas']}, "
                      f"into-rodata={rel['differing_addends_pointing_into_rodata']}")
        multiset = elf.get("printable_string_multiset", {})
        if multiset.get("removed") or multiset.get("added"):
            print(f"  printable string multiset: removed={multiset.get('removed')[:5]} "
                  f"added={multiset.get('added')[:5]}")
        meta = elf.get("metadata_strings", {})
        if meta.get("source_only") or meta.get("rebuild_only"):
            print(f"  build metadata strings changed: "
                  f"{meta.get('source_only')} -> {meta.get('rebuild_only')}")
        print(f"  verdict: {verdict.get('class')} - {verdict.get('reason')}")
    for name, entry in entries.get("dex_files", {}).items():
        dex = entry.get("dex", {})
        verdict = dex.get("verdict", {})
        print(f"\n[DEX] {name}")
        print(f"  byte differences: {dex.get('byte_differences', {}).get('total_bytes')} "
              f"in {dex.get('byte_differences', {}).get('range_count')} ranges")
        print(f"  string order positional differences: "
              f"{dex.get('strings', {}).get('positional_difference_count')}")
        chm = dex.get("class_hash_map_metadata", {})
        if "detail" in chm:
            print(f"  class-hash map: entries {chm['detail']['entry_count']}, "
                  f"differing {chm['detail'].get('differing_entry_count')}")
            for change in chm["detail"].get("differing_entries", [])[:5]:
                print(f"    {change['key']}: {change['source']} -> {change['rebuild']}")
        dd = dex.get("dexdump_disassembly_diff", {})
        if dd.get("categories"):
            print(f"  dexdump diff categories: {dd['categories']}")
        print(f"  verdict: {verdict.get('class')} - {verdict.get('reason')}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Compare two APKs built from the same source to root-cause "
                    "non-deterministic bytes (native libraries, DEX, ZIP layout).")
    parser.add_argument("--source", required=True,
                        help="APK saved from the first build")
    parser.add_argument("--rebuild", required=True,
                        help="APK produced by the rebuild")
    parser.add_argument("--workdir", default=os.getcwd(),
                        help="directory for extracted files and dump logs "
                             "(default: current directory)")
    parser.add_argument("--json-out", default=None,
                        help="write the full JSON report here")
    parser.add_argument("--llvm-bin", default=None,
                        help="directory containing NDK llvm-objdump "
                             "(default: auto-discover from ANDROID_NDK_HOME/SDK)")
    parser.add_argument("--dexdump", default=None,
                        help="path to build-tools dexdump "
                             "(default: auto-discover from Android SDK)")
    parser.add_argument("--quiet", action="store_true",
                        help="only write the JSON report, do not print a summary")
    args = parser.parse_args(argv)

    report = build_report(args)
    if args.json_out:
        out = Path(args.json_out).resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"report: {out}")
    if not args.quiet:
        print_summary(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
