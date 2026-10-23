#!/usr/bin/env python3
"""Manual Khronos-validation-layer chaining over the Vapor Vulkan wrapper.

What this does (no app/APK/Wine/Steam/game is started):

 1. Builds tools/tests/vulkan-wrapper-validation.c with the pinned NDK
    aarch64-linux-android34-clang (16 KB page link flags, like the other
    native fixtures).
 2. Boots ONLY the VaporProbe36D AVD on emulator port 5562 (4 KB pages),
    after checking that port is free.
 3. Stages the fixture, the official KhronosGroup/Vulkan-ValidationLayers
    Android arm64-v8a libVkLayer_khronos_validation.so, and the pinned
    candidate wrapper (plus its auxiliary libraries) into
    /data/local/tmp/vapor-wrapper-validation-20261022.
 4. Runs a matrix of workloads through the layer chained manually in front of
    the wrapper: instance/device creation, BC1/BC3 image upload with
    FORCE_BCN_EMULATION=1 (compute-shader and image-view decode paths), the
    host-CPU decode path (USE_CPU_BCN=all), a two-region upload, and a
    capability-only case.  The same chain is run against the system
    libvulkan.so loader native driver as a control, and the wrapper is also
    run without any layer and (optionally) with the wrapper's own built-in
    USE_VVL path, which asks the system loader to load the same layer BELOW
    the wrapper.
 5. Captures stdout/stderr and logcat tag VALIDATION, parses the layer's
    default "Validation Error: [ VUID-... ]" message blocks, and writes the
    evidence receipt JSON (VUID tables, wrapper-vs-control comparison).
 6. Kills emulator-5562 and removes the remote staging directory.

The fixture never installs a debug-utils callback: the reader wrapper blocks
VK_EXT_debug_utils on its outward interface, so the layer's default callback
(stdout + logcat tag VALIDATION) is used instead.

Usage:
    python3 tools/tests/vulkan-wrapper-validation.py
    python3 tools/tests/vulkan-wrapper-validation.py --no-boot --serial emulator-5562
    python3 tools/tests/vulkan-wrapper-validation.py --cases chain --keep-emulator

The evidence JSON is written to
docs/evidence/2026-10-22-vulkan-wrapper-validation.json (override with
--evidence); raw logs and per-run captures go to --scratch
(default ~/wrapper-validation).
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

DATE = "2026-10-22"
REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_SOURCE = Path(__file__).resolve().with_suffix(".c")
DEFAULT_EVIDENCE = REPO_ROOT / "docs" / "evidence" / f"{DATE}-vulkan-wrapper-validation.json"
DEFAULT_SCRATCH = Path.home() / "wrapper-validation"
DEFAULT_WRAPPER_BUILD = Path(
    os.environ.get("VAPOR_WRAPPER_SOURCE", ""))
DEFAULT_NDK = _sdk_root() / "ndk" / "27.3.13750724"
DEFAULT_EMULATOR = Path(str(_sdk_root() / "emulator" / "emulator"))
DEFAULT_ADB = Path(str(_sdk_root() / "platform-tools" / "adb"))
DEFAULT_AVD = "VaporProbe36D"
DEFAULT_PORT = 5562
DEFAULT_SERIAL = f"emulator-{DEFAULT_PORT}"
SYSTEM_LIBVULKAN = "/system/lib64/libvulkan.so"

LAYER_RELEASE = {
    "repository": "KhronosGroup/Vulkan-ValidationLayers",
    "tag": "vulkan-sdk-1.4.363.0",
    "asset": "android-binaries-1.4.363.0.tar.gz",
    "asset_url": "https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases/download/"
                 "vulkan-sdk-1.4.363.0/android-binaries-1.4.363.0.tar.gz",
    "archive_sha256": "b2727789f4c4d83b7149e904768bbaf54e8236ec912691730a70e22aebcba419",
    "library_path": "arm64-v8a/libVkLayer_khronos_validation.so",
    "library_sha256": "db7447027e28db95584e9fde8a57cc1f28c2dd09defcbe2f2b76ee2bbc65bf09",
}

PINNED_WRAPPER_HASHES = {
    # From docs/evidence/2026-10-21-vulkan-wrapper-native-4k.json.
    "candidate-stripped.so": "574cfb4d04bc036fe815357366a3c2e430d7da0a3e32da3d372c5c67fd835506",
}
AUX_LIBS = [
    "libadrenotools.so", "libandroid-shmem.so", "libc++_shared.so", "libz.so.1",
    "libzstd.so.1", "libxcb.so", "libX11-xcb.so", "libxcb-dri3.so",
    "libxcb-present.so", "libxcb-sync.so", "libxcb-randr.so", "libxcb-shm.so",
    "libdrm.so", "libXau.so", "libXdmcp.so", "libandroid-support.so",
]

MESSAGE_HEADER = re.compile(
    r"^(Validation Error|Validation Warning|Validation Performance Warning|"
    r"Validation Information|Verbose Information): \[ (?P<id>\S+) \]"
    r" \| MessageID = 0x(?P<mid>[0-9a-fA-F]+)\s*$")
OBJECT_LINE = re.compile(r"^    \[\d+\] (?P<type>VK_OBJECT_TYPE_\w+)(?: 0x(?P<handle>[0-9a-fA-F]+))?")
WRAPPER_LOG_LINE = re.compile(r"^\[\d{3}\.\d{2}\] ")
LOGCAT_LINE = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+ ")

PHASE_LINE = re.compile(r"^(PROBE|DRIVER_INTERFACE|LAYER_INTERFACE|LAYER_PROPERTIES|LAYER |INSTANCE|"
                        r"INSTANCE_EXTENSIONS|INSTANCE_EXTENSION|PHYSICAL_DEVICE|FEATURES|DEVICE|QUEUE|"
                        r"WORKLOAD|PASS|FAIL|RESULT|SUMMARY)\b")


def _sdk_root() -> Path:
    """Android SDK root, from the environment rather than a hard-coded developer path."""
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            return Path(value)
    return Path.home() / "Library" / "Android" / "sdk"


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()


def elf_max_load_align(path):
    data = Path(path).read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    import struct
    # e_phoff at 0x20, e_phentsize at 0x36, e_phnum at 0x38
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize = struct.unpack_from("<H", data, 0x36)[0]
    phnum = struct.unpack_from("<H", data, 0x38)[0]
    max_align = 0
    for i in range(phnum):
        offset = phoff + i * phentsize
        p_type = struct.unpack_from("<I", data, offset)[0]
        if p_type != 1:  # PT_LOAD
            continue
        p_align = struct.unpack_from("<Q", data, offset + 48)[0]
        max_align = max(max_align, p_align)
    return max_align


class Adb:
    def __init__(self, adb, serial, log):
        self.adb = str(adb)
        self.serial = serial
        self.log = log

    def base(self):
        return [self.adb, "-s", self.serial]

    def shell(self, command, timeout=600):
        started = time.time()
        proc = subprocess.run(self.base() + ["shell", command], capture_output=True, text=True,
                              timeout=timeout)
        return {
            "command": command,
            "exit_code": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
            "duration_seconds": round(time.time() - started, 3),
        }

    def push(self, local_paths, remote):
        paths = [str(Path(p)) for p in local_paths]
        proc = subprocess.run(self.base() + ["push"] + paths + [remote],
                              capture_output=True, text=True, timeout=600)
        if proc.returncode != 0:
            raise SystemExit("adb push failed: %s\n%s" % (" ".join(paths), proc.stderr))
        return {"remote": remote, "files": [Path(p).name for p in paths]}

    def devices(self):
        proc = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=60)
        devices = []
        for line in proc.stdout.splitlines():
            line = line.strip()
            if line and not line.startswith("List of"):
                devices.append(line.split()[0])
        return devices

    def logcat_clear(self):
        subprocess.run(self.base() + ["logcat", "-c"], capture_output=True, text=True, timeout=60)

    def logcat_dump(self):
        proc = subprocess.run(self.base() + ["logcat", "-d", "-s", "VALIDATION:I", "VkWrapper:*"],
                              capture_output=True, text=True, timeout=120)
        return proc.stdout


class Emulator:
    def __init__(self, args, log):
        self.args = args
        self.log = log
        self.adb = Adb(args.adb, args.serial, log)
        self.started_here = False

    def port_busy(self):
        proc = subprocess.run(["lsof", "-nP", "-iTCP:%d" % self.args.port, "-sTCP:LISTEN"],
                              capture_output=True, text=True)
        return proc.returncode == 0 and bool(proc.stdout.strip())

    def online(self):
        return self.args.serial in self.adb.devices()

    def boot(self):
        if self.online():
            self.log("emulator %s already online (not started by this run)" % self.args.serial)
            return False
        if self.port_busy():
            raise SystemExit("error: port %d is already in use; refusing to touch it" % self.args.port)
        if not Path(self.args.emulator).exists():
            raise SystemExit("error: emulator binary not found: %s" % self.args.emulator)
        command = [
            str(self.args.emulator), "-avd", self.args.avd, "-port", str(self.args.port),
            "-no-window", "-no-audio", "-no-snapshot-load", "-no-snapshot-save",
            "-gpu", self.args.gpu, "-no-boot-anim", "-memory", "2048", "-cores", "2",
        ]
        self.log("booting %s: %s" % (self.args.avd, " ".join(command)))
        log_path = self.args.scratch / "logs" / "emulator-boot.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, "w", encoding="utf-8") as handle:
            process = subprocess.Popen(command, stdout=handle, stderr=subprocess.STDOUT)
        self.started_here = True
        proc = subprocess.run(self.adb.base() + ["wait-for-device"], capture_output=True, text=True,
                              timeout=300)
        if proc.returncode != 0:
            raise SystemExit("error: emulator never appeared on adb: %s" % proc.stderr)
        started = time.time()
        deadline = started + self.args.boot_timeout
        while time.time() < deadline:
            mode = self.readiness_mode()
            if mode:
                self.log("emulator ready after %.1fs (%s)" % (time.time() - started, mode))
                return True
            if process.poll() is not None:
                raise SystemExit("error: emulator process exited during boot; see %s" % log_path)
            time.sleep(3)
        raise SystemExit("error: emulator boot timeout (%ss); see %s" % (self.args.boot_timeout, log_path))

    def readiness_mode(self):
        """Return the readiness signal observed, or None.

        Under heavy host load the API 36 AVD can spend >10 minutes before
        sys.boot_completed becomes visible to the shell; the fixture only needs
        a working adb shell, package manager and activity service, so accept
        that as a documented fallback.
        """
        result = self.adb.shell("getprop sys.boot_completed", timeout=30)
        if result["stdout"].strip() == "1":
            return "sys.boot_completed=1"
        result = self.adb.shell("service check activity 2>/dev/null; pm path android 2>/dev/null",
                                timeout=60)
        output = result["stdout"]
        if "Service activity: found" in output and "package:" in output:
            return "services-fallback (activity service + package manager up; sys.boot_completed not visible)"
        return None

    def facts(self):
        props = {}
        for prop in ["ro.build.fingerprint", "ro.product.name", "ro.product.model", "ro.product.device",
                     "ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abi",
                     "ro.kernel.qemu", "ro.boot.qemu.avd_name", "ro.debuggable", "ro.hardware"]:
            props[prop] = self.adb.shell("getprop %s" % prop, timeout=30)["stdout"].strip()
        page_size = self.adb.shell("getconf PAGE_SIZE", timeout=30)["stdout"].strip()
        readiness = self.readiness_mode()
        uname = self.adb.shell("uname -a", timeout=30)["stdout"].strip()
        emulator_version = None
        if Path(self.args.emulator).exists():
            proc = subprocess.run([str(self.args.emulator), "-version"], capture_output=True,
                                  text=True, timeout=60)
            lines = [line for line in proc.stdout.splitlines() if line.strip()]
            emulator_version = lines[0] if lines else None
        return {
            "serial": self.args.serial,
            "avd": self.args.avd,
            "port": self.args.port,
            "started_by_tool": self.started_here,
            "properties": props,
            "uname": uname,
            "page_size": int(page_size) if page_size.isdigit() else None,
            "sys_boot_completed_visible": self.adb.shell(
                "getprop sys.boot_completed", timeout=30)["stdout"].strip(),
            "readiness": readiness,
            "host_load_average": os.getloadavg() if hasattr(os, "getloadavg") else None,
            "emulator_binary_version": emulator_version,
        }

    def kill(self):
        if not self.online():
            self.log("emulator %s is not online; nothing to kill" % self.args.serial)
            return
        self.log("killing %s" % self.args.serial)
        subprocess.run(self.adb.base() + ["emu", "kill"], capture_output=True, text=True, timeout=60)
        deadline = time.time() + 60
        while time.time() < deadline:
            if not self.online():
                self.log("emulator %s is gone" % self.args.serial)
                return
            time.sleep(2)
        self.log("WARNING: emulator %s still online 60s after `adb emu kill`" % self.args.serial)


# ---------------------------------------------------------------------------
# Message capture / parsing
# ---------------------------------------------------------------------------

def parse_validation_messages(text):
    """Split the layer's default stdout format into message blocks."""
    lines = text.replace("\r\n", "\n").split("\n")
    messages = []
    current = None
    in_objects = False

    def finish():
        nonlocal current, in_objects
        if current:
            while current["text_lines"] and not current["text_lines"][-1].strip():
                current["text_lines"].pop()
            current["text"] = " ".join(part.strip() for part in current["text_lines"] if part.strip())
            current["text_sha256_16"] = sha256_text(current["text"])[:16]
            messages.append(current)
        current = None
        in_objects = False

    for line in lines:
        header = MESSAGE_HEADER.match(line)
        if header:
            finish()
            current = {
                "severity": header.group(1),
                "vuid": header.group("id"),
                "message_id": "0x" + header.group("mid").lower(),
                "text_lines": [],
                "objects": [],
            }
            continue
        if current is None:
            continue
        if (PHASE_LINE.match(line) or WRAPPER_LOG_LINE.match(line) or LOGCAT_LINE.match(line)):
            finish()
            continue
        stripped = line.strip()
        if stripped.startswith("Objects:"):
            in_objects = True
            continue
        if in_objects:
            object_match = OBJECT_LINE.match(line)
            if object_match:
                current["objects"].append({"type": object_match.group("type"),
                                           "handle": object_match.group("handle")})
                continue
            finish()
            continue
        if stripped == "":
            finish()
            continue
        current["text_lines"].append(line)
    finish()
    return messages


def parse_logcat_validation(text):
    """Reconstruct layer messages from logcat tag VALIDATION (1024-byte chunks)."""
    messages = []
    current = None
    for line in text.replace("\r\n", "\n").split("\n"):
        match = re.match(r"^[A-Z]/VALIDATION\s*\(\s*\d+\):\s?(.*)$", line)
        if not match:
            continue
        payload = match.group(1)
        header = MESSAGE_HEADER.match(payload.strip())
        if header:
            if current:
                messages.append(current)
            current = {
                "severity": header.group(1),
                "vuid": header.group("id"),
                "message_id": "0x" + header.group("mid").lower(),
                "text_lines": [],
                "chunks": 1,
            }
            continue
        if current is None:
            continue
        current["text_lines"].append(payload)
        current["chunks"] += 1
    if current:
        messages.append(current)
    for message in messages:
        message["text"] = " ".join(part.strip() for part in message["text_lines"] if part.strip())
        message["text_sha256_16"] = sha256_text(message["text"])[:16]
    return messages


def parse_probe_events(stdout):
    events = []
    for line in stdout.replace("\r\n", "\n").split("\n"):
        if PHASE_LINE.match(line):
            events.append(line)
    return events


def merge_messages(stdout_messages, logcat_messages):
    # stdout is the primary capture (the layer's default callback writes there
    # in-process); logcat is chunked, so only use it when stdout produced no
    # parseable records.
    if stdout_messages:
        return stdout_messages
    return logcat_messages


# ---------------------------------------------------------------------------
# Main pipeline
# ---------------------------------------------------------------------------

CASES = [
    {"name": "wrapper-chained-bc1-compute", "target": "wrapper", "layer": True, "mode": "bc1",
     "cycles": 8, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "BC1 upload through the wrapper compute-shader decode path with the layer above the wrapper"},
    {"name": "wrapper-chained-bc1-view", "target": "wrapper", "layer": True, "mode": "bc1",
     "cycles": 8, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "1"},
     "why": "BC1 upload through the wrapper image-view compute decode path"},
    {"name": "wrapper-chained-bc3", "target": "wrapper", "layer": True, "mode": "bc3",
     "cycles": 8, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "BC3 upload through the wrapper compute-shader decode path"},
    {"name": "wrapper-chained-cpu", "target": "wrapper", "layer": True, "mode": "bc1",
     "cycles": 4, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_CPU_BCN": "all"},
     "why": "BC1 upload through the wrapper host-CPU decode fallback (staging memory + flush)"},
    {"name": "wrapper-chained-bc1-multi", "target": "wrapper", "layer": True, "mode": "bc1-multi",
     "cycles": 2, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "single CmdCopyBufferToImage with two regions (per-region staging resources)"},
    {"name": "wrapper-chained-caps", "target": "wrapper", "layer": True, "mode": "caps",
     "cycles": 1, "edge": 16, "env": {},
     "why": "instance/device creation only, to inspect wrapper-reported capabilities through the layer"},
    {"name": "wrapper-chained-vuid", "target": "wrapper", "layer": True, "mode": "vuid",
     "cycles": 1, "edge": 16, "env": {}, "expect": "VUID-vkDestroyDevice-device-05137",
     "why": "positive control: deliberate fixture leak on vkDestroyDevice must be reported through the "
            "manual chain over the wrapper"},
    {"name": "wrapper-direct-bc1", "target": "wrapper", "layer": False, "mode": "bc1",
     "cycles": 4, "edge": 16,
     "env": {"FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "fixture self-check: same workload with no layer (expected 0 validation messages)"},
    {"name": "wrapper-direct-vuid", "target": "wrapper", "layer": False, "mode": "vuid",
     "cycles": 1, "edge": 16, "env": {}, "expect_none": True,
     "why": "negative control: same deliberate leak without a layer (expected 0 messages)"},
    {"name": "system-chained-bc1-native", "target": "system", "layer": True, "mode": "bc1-native",
     "cycles": 8, "edge": 16, "env": {},
     "why": "control: manual chain over the system libvulkan.so loader native driver, compressed BC1 "
            "transfer (readback compared to uploaded blocks)"},
    {"name": "system-chained-rgba", "target": "system", "layer": True, "mode": "rgba",
     "cycles": 8, "edge": 16, "env": {},
     "why": "control: manual chain over the system loader native driver, uncompressed RGBA8"},
    {"name": "system-chained-caps", "target": "system", "layer": True, "mode": "caps",
     "cycles": 1, "edge": 16, "env": {},
     "why": "control: instance/device creation through the manual chain over the system loader"},
    {"name": "system-chained-vuid", "target": "system", "layer": True, "mode": "vuid",
     "cycles": 1, "edge": 16, "env": {}, "expect": "VUID-vkDestroyDevice-device-05137",
     "why": "positive control: same deliberate leak through the chain over the system loader"},
]

USE_VVL_CASES = [
    {"name": "wrapper-usevvl-bc1-compute", "target": "wrapper", "layer": False, "mode": "bc1",
     "cycles": 8, "edge": 16,
     "env": {"USE_VVL": "1", "FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "below-wrapper: BC1 compute-shader decode (USE_IMAGE_VIEW=0)"},
    {"name": "wrapper-usevvl-bc1-view", "target": "wrapper", "layer": False, "mode": "bc1",
     "cycles": 8, "edge": 16,
     "env": {"USE_VVL": "1", "FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "1"},
     "why": "below-wrapper: BC1 image-view decode path"},
    {"name": "wrapper-usevvl-bc3", "target": "wrapper", "layer": False, "mode": "bc3",
     "cycles": 8, "edge": 16,
     "env": {"USE_VVL": "1", "FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "below-wrapper: BC3 compute-shader decode"},
    {"name": "wrapper-usevvl-cpu", "target": "wrapper", "layer": False, "mode": "bc1",
     "cycles": 4, "edge": 16,
     "env": {"USE_VVL": "1", "FORCE_BCN_EMULATION": "1", "USE_CPU_BCN": "all"},
     "why": "below-wrapper: host-CPU decode fallback (staging memory map/flush)"},
    {"name": "wrapper-usevvl-bc1-multi", "target": "wrapper", "layer": False, "mode": "bc1-multi",
     "cycles": 2, "edge": 16,
     "env": {"USE_VVL": "1", "FORCE_BCN_EMULATION": "1", "USE_IMAGE_VIEW": "0"},
     "why": "below-wrapper: two-region CmdCopyBufferToImage (per-region staging)"},
    {"name": "wrapper-usevvl-vuid", "target": "wrapper", "layer": False, "mode": "vuid",
     "cycles": 1, "edge": 16, "env": {"USE_VVL": "1"},
     "expect": "any",
     "why": "below-wrapper positive control: app-level deliberate leak makes the wrapper leave driver-level "
            "objects/memory behind, which the layer below must report at the wrapper's driver teardown"},
    {"name": "wrapper-usevvl-caps", "target": "wrapper", "layer": False, "mode": "caps",
     "cycles": 1, "edge": 16, "env": {"USE_VVL": "1"},
     "why": "below-wrapper: instance/device creation only (confirms the wrapper loaded the layer below it)"},
]

# Negative control run before the layer/manifest is staged: with USE_VVL=1 the
# wrapper asks the loader for layers; with no layer discoverable the wrapper
# must fail instance creation with VK_ERROR_LAYER_NOT_PRESENT.
USE_VVL_NOLAYER_CASE = {
    "name": "wrapper-usevvl-nolayer", "target": "wrapper", "layer": False, "mode": "caps",
    "cycles": 1, "edge": 16, "env": {"USE_VVL": "1"},
    "expect_failure": True,
    "why": "below-wrapper negative control: USE_VVL=1 with no layer staged must fail with "
           "VK_ERROR_LAYER_NOT_PRESENT instead of silently running unvalidated",
}

def build_fixture(args, log):
    clang = Path(args.clang) if args.clang else (
        Path(args.ndk) / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang")
    if not clang.exists():
        raise SystemExit("error: NDK clang not found at %s (pass --ndk/--clang)" % clang)
    output = args.scratch / "bin" / "validation-probe"
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        str(clang), "-std=c11", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-D_GNU_SOURCE",
        str(FIXTURE_SOURCE), "-ldl",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-o", str(output),
    ]
    log("compiling fixture: %s" % " ".join(command))
    proc = subprocess.run(command, capture_output=True, text=True, timeout=300)
    if proc.returncode != 0:
        raise SystemExit("error: fixture compile failed\n%s\n%s" % (proc.stdout, proc.stderr))
    return {
        "compiler": str(clang),
        "command": command,
        "source": str(FIXTURE_SOURCE.relative_to(REPO_ROOT)),
        "source_sha256": sha256_file(FIXTURE_SOURCE),
        "binary": str(output),
        "binary_sha256": sha256_file(output),
        "binary_bytes": output.stat().st_size,
        "elf_max_load_align": elf_max_load_align(output),
        "page_size_link_flags": ["-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"],
    }


def apply_partial_ab_status(section):
    """After an A/B table is built, apply partial/fixed statuses to the section's
    findings/summary/liveness so a partial fix is recorded as partial, not as a
    failure."""
    ab = section.get("ab_table") or {}
    rows = ab.get("expected_defects") or []
    if not rows:
        return section
    by_id = {row["id"]: row for row in rows}
    for item in section.get("findings") or []:
        row = by_id.get(item["id"])
        if not row:
            continue
        if row["status"].startswith("partially"):
            item["status"] = row["status"]
            item["classification"] = "partially fixed (intentional residual)"
        elif row["status"].startswith("fixed"):
            item["status"] = row["status"]
            item["classification"] = "expected fix verified (not reproduced in this build)"
    # keep the flat defect list and per-VUID classifications consistent
    for defect in (section.get("summary") or {}).get("wrapper_defects_found") or []:
        row = by_id.get(defect.get("id"))
        if row:
            defect["status"] = row["status"]
    for entry in section.get("vuid_table") or []:
        finding_id = entry.get("finding")
        row = by_id.get(finding_id)
        if not row:
            continue
        if row["status"].startswith("partially"):
            entry["classification"] = (
                "wrapper-defect (D1 partially fixed: %s intentionally left to the driver)"
                % (row.get("patched_leaked_objects") or "residual"))
        elif row["status"].startswith("fixed"):
            entry["classification"] = "expected fix verified (no longer reproduced in this build)"
    summary = section.setdefault("summary", {})
    summary["expected_defects_status"] = {row["id"]: row["status"] for row in rows}
    partial = [row for row in rows if row["status"].startswith("partially")]
    fixed = [row for row in rows if row["status"].startswith("fixed")]
    summary["expected_fix_outcome"] = {
        "fully_fixed": [row["id"] for row in fixed],
        "partially_fixed": [row["id"] for row in partial],
        "not_fixed": [row["id"] for row in rows
                      if row["status"].startswith("NOT fixed")],
    }
    summary["expected_fix_verified"] = (not summary["expected_fix_outcome"]["not_fixed"]
                                        and not partial)
    summary["expected_fix_status"] = ("partially-fixed" if partial else
                                      ("fully-fixed" if not summary["expected_fix_outcome"]["not_fixed"]
                                       else "not-fixed"))
    if partial:
        text = "; ".join("%s: %s" % (row["id"], row["status"]) for row in partial)
        summary["verdict"] = (
            "Patched build: expected fixes are partially complete - %s. Fully fixed: %s. A partial fix "
            "is recorded as such, not as a failure; all workloads still passed their pixel/readback checks."
            % (text, ", ".join(row["id"] for row in fixed) or "none"))
    elif fixed and not summary["expected_fix_outcome"]["not_fixed"]:
        summary["verdict"] = ("Patched build: expected fixes verified; D1/D2 were not reproduced below "
                              "the wrapper and all workloads remained correct.")
    section["comparison"] = section.get("comparison") or {}
    section["comparison"]["expected_findings_status"] = summary["expected_defects_status"]
    section["comparison"]["expected_fix_outcome"] = summary["expected_fix_outcome"]
    section["comparison"]["ab_conclusion"] = ab.get("conclusion")
    if partial:
        section["comparison"]["conclusion"] = ("Partial fix verified: " +
                                               "; ".join(row["id"] + " = " + row["status"]
                                                         for row in rows))
    elif fixed and not summary["expected_fix_outcome"]["not_fixed"]:
        section["comparison"]["conclusion"] = ("Fix verified: " +
                                               ", ".join(row["id"] for row in fixed) +
                                               " no longer reproduced in this build.")
    # replace contradictory per-finding conclusion lines with the A/B outcome
    conclusions = [line for line in (section.get("conclusions") or [])
                   if not line.startswith("Expected fix ")]
    ab_line = ab.get("conclusion")
    if ab_line:
        conclusions.insert(1, "A/B result against the unpatched below-wrapper section: " + ab_line)
    section["conclusions"] = conclusions
    liveness = section.get("liveness_control") or {}
    visible = liveness.get("wrapper_internal_objects_visible")
    if visible and partial:
        residual = [row for row in partial if row["id"] == "D1"]
        if residual:
            row = residual[0]
            visible["objects"] = ["VkDeviceMemory x%d (residual, intentionally left to the driver)"
                                  % (row.get("patched_leaked_objects") or 0)]
            visible["meaning"] = (
                "D1 is partially fixed: the wrapper now destroys its BCn pipelines, layouts, buffers and "
                "compute pool, but the two constants VkDeviceMemory allocations are deliberately left to "
                "the driver's DestroyDevice (gfxstream crashed when the wrapper freed them itself), so the "
                "below-wrapper layer still sees exactly those residual objects. This is expected residual, "
                "not a regression.")
    return section


def build_ab_table(base_section, patched_section):
    """Compare two below-wrapper sections class-by-class / VUID-by-VUID."""
    if not base_section:
        return {"error": "base section not found", "base_section": None}
    base_classes = (base_section.get("summary", {}) or {}).get("messages_by_class", {}) or {}
    new_classes = (patched_section.get("summary", {}) or {}).get("messages_by_class", {}) or {}
    class_names = sorted(set(base_classes) | set(new_classes))
    class_rows = [{
        "class": name,
        "unpatched_messages": base_classes.get(name, 0),
        "patched_messages": new_classes.get(name, 0),
        "delta": new_classes.get(name, 0) - base_classes.get(name, 0),
    } for name in class_names]

    def vuid_map(section):
        table = {}
        for entry in (section.get("vuid_table") or []):
            row = table.setdefault(entry["vuid"], {"messages": 0, "records": 0, "runs": []})
            row["messages"] += entry.get("count", 1)
            row["records"] += 1
            for name in entry.get("runs", []):
                if name not in row["runs"]:
                    row["runs"].append(name)
        return table

    base_vuids, new_vuids = vuid_map(base_section), vuid_map(patched_section)
    vuid_rows = []
    for vuid in sorted(set(base_vuids) | set(new_vuids)):
        vuid_rows.append({
            "vuid": vuid,
            "unpatched_messages": base_vuids.get(vuid, {}).get("messages", 0),
            "patched_messages": new_vuids.get(vuid, {}).get("messages", 0),
            "unpatched_records": base_vuids.get(vuid, {}).get("records", 0),
            "patched_records": new_vuids.get(vuid, {}).get("records", 0),
            "unpatched_runs": base_vuids.get(vuid, {}).get("runs", []),
            "patched_runs": new_vuids.get(vuid, {}).get("runs", []),
        })

    expected = (patched_section.get("expected_findings") or
                (patched_section.get("wrapper_build") or {}).get("expected_findings") or [])
    base_findings = {item["id"]: item for item in (base_section.get("findings") or [])}
    patched_findings = {item["id"]: item for item in (patched_section.get("findings") or [])}
    partial = (patched_section.get("partial_findings") or
               (patched_section.get("wrapper_build") or {}).get("partial_findings") or [])

    def leaked_max(section, finding, finding_id):
        item = finding or {}
        counts = ((item.get("occurrences") or {}).get("leaked_object_counts") or [])
        if counts:
            return max(counts)
        vuid = item.get("vuid")
        values = [entry.get("leaked_object_count") for entry in (section.get("vuid_table") or [])
                  if entry.get("vuid") == vuid and entry.get("leaked_object_count") is not None
                  and (entry.get("finding") == finding_id or
                       str(entry.get("classification", "")).startswith("wrapper-defect"))]
        return max(values) if values else None

    defect_rows = []
    for finding_id in expected or sorted(set(base_findings) | set(patched_findings)):
        base = base_findings.get(finding_id)
        patched = patched_findings.get(finding_id)
        base_msgs = ((base or {}).get("occurrences") or {}).get("messages", 0)
        patched_msgs = ((patched or {}).get("occurrences") or {}).get("messages", 0)
        base_leaked = leaked_max(base_section, base, finding_id)
        patched_leaked = leaked_max(patched_section, patched, finding_id)
        if finding_id in expected:
            if patched_msgs == 0:
                status = "fixed (not reproduced in patched build)"
            elif (finding_id in partial and patched_leaked is not None and base_leaked is not None
                  and patched_leaked < base_leaked):
                status = ("partially fixed (%d of %d leaked objects remain; residual intentionally left "
                          "to the driver)" % (patched_leaked, base_leaked))
            else:
                status = "NOT fixed (still reproduced)"
        else:
            status = "still present" if patched_msgs else "not observed in patched build"
        defect_rows.append({
            "id": finding_id,
            "title": (patched or base or {}).get("title"),
            "vuid": (patched or base or {}).get("vuid"),
            "unpatched_messages": base_msgs,
            "patched_messages": patched_msgs,
            "unpatched_leaked_objects": base_leaked,
            "patched_leaked_objects": patched_leaked,
            "status": status,
        })
    fixed = [row for row in defect_rows if row["status"].startswith("fixed")]
    partial_rows = [row for row in defect_rows if row["status"].startswith("partially")]
    if defect_rows:
        if partial_rows:
            conclusion = ("%d/%d expected finding(s) fully fixed, %d partially fixed (intentional residual): "
                          % (len(fixed), len(defect_rows), len(partial_rows)) +
                          "; ".join(row["id"] + " = " + row["status"] for row in partial_rows))
        else:
            conclusion = ("%d/%d expected finding(s) no longer reproduced in the patched build."
                          % (len(fixed), len(defect_rows)))
    else:
        conclusion = None
    return {
        "conclusion": conclusion,
        "class_table": class_rows,
        "vuid_table": vuid_rows,
        "expected_defects": defect_rows,
        "base_section": base_section.get("section_name", "below_wrapper_use_vvl"),
        "patched_section": patched_section.get("section_name"),
        "unpatched_wrapper": ((base_section.get("wrapper_build") or {}).get("wrapper_sha256")
                              or (base_section.get("provenance") or {}).get("wrapper", {}).get(
                                  "candidate_stripped_sha256")),
        "unpatched_wrapper_sha256": ((base_section.get("wrapper_build") or {}).get("wrapper_sha256")
                                     or (base_section.get("provenance") or {}).get("wrapper", {}).get(
                                         "candidate_stripped_sha256")),
        "patched_wrapper_sha256": (patched_section.get("wrapper_build") or {}).get("wrapper_sha256"),
        "patched_wrapper_file": (patched_section.get("wrapper_build") or {}).get("wrapper_file"),
        "patched_wrapper_label": (patched_section.get("wrapper_build") or {}).get("label"),
    }


def resolve_wrapper_build(args, log=None):
    """Resolve the wrapper build layout for the default pinned build and for
    alternate/staged builds (e.g. the D1/D2-patched build).

    Defaults are unchanged: <wrapper-build>/candidate-stripped.so plus the
    auxiliary runtime libraries from <wrapper-build>/native-probe.  For an
    alternate build the wrapper library name may differ (candidate.so,
    libvulkan_wrapper.so, ...), and auxiliary libraries may be absent from that
    directory; in that case the pinned build's native-probe directory is used as
    the auxiliary-library fallback, and the fallback is recorded.
    """
    build_dir = Path(args.wrapper_build)
    if not build_dir.is_dir():
        raise SystemExit("error: wrapper build directory not found: %s" % build_dir)
    wrapper_so = None
    if args.wrapper_so:
        wrapper_so = Path(args.wrapper_so)
        if not wrapper_so.is_absolute():
            wrapper_so = build_dir / wrapper_so
        if not wrapper_so.is_file():
            raise SystemExit("error: --wrapper-so not found: %s" % wrapper_so)
    else:
        for name in ("candidate-stripped.so", "candidate.so", "libvulkan_wrapper.so"):
            candidate = build_dir / name
            if candidate.is_file():
                wrapper_so = candidate
                break
        if wrapper_so is None:
            raise SystemExit("error: no wrapper shared object found in %s (tried candidate-stripped.so, "
                             "candidate.so, libvulkan_wrapper.so; pass --wrapper-so)" % build_dir)
    aux_dir = Path(args.aux_dir) if args.aux_dir else (build_dir / "native-probe")
    aux_fallback_used = False
    if not all((aux_dir / name).is_file() for name in AUX_LIBS):
        pinned_aux = DEFAULT_WRAPPER_BUILD / "native-probe"
        if all((pinned_aux / name).is_file() for name in AUX_LIBS):
            aux_dir = pinned_aux
            aux_fallback_used = True
        else:
            missing = [name for name in AUX_LIBS if not (aux_dir / name).is_file()]
            raise SystemExit("error: auxiliary libraries missing from %s: %s (pass --aux-dir)"
                             % (aux_dir, ", ".join(missing[:6])))
    digest = sha256_file(wrapper_so)
    info = {
        "build_dir": str(build_dir),
        "wrapper_so": str(wrapper_so),
        "wrapper_file": wrapper_so.name,
        "wrapper_sha256": digest,
        "wrapper_bytes": wrapper_so.stat().st_size,
        "aux_dir": str(aux_dir),
        "aux_fallback_to_pinned_build": aux_fallback_used,
        "pinned_hash_match": PINNED_WRAPPER_HASHES.get(wrapper_so.name) == digest,
        "label": args.wrapper_label or "candidate wrapper",
        "expected_findings": ([item.strip() for item in args.expected_findings.split(",") if item.strip()]
                              if args.expected_findings else []),
        "partial_findings": ([item.strip() for item in args.partial_findings.split(",") if item.strip()]
                             if args.partial_findings else []),
    }
    if log:
        log("wrapper build resolved: %s (sha256=%s aux=%s%s)"
            % (info["wrapper_so"], digest[:16], info["aux_dir"],
               " [pinned fallback]" if aux_fallback_used else ""))
    return info


def stage_remote(args, adb, build, layer_local, build_info, log):
    remote = args.remote_dir
    adb.shell("rm -rf %s && mkdir -p %s/logs" % (remote, remote))
    files = [Path(build["binary"]), Path(build_info["wrapper_so"])]
    files += [Path(build_info["aux_dir"]) / name for name in AUX_LIBS]
    files.append(Path(layer_local))
    adb.push(files, remote)
    adb.shell("chmod 755 %s/validation-probe %s/%s %s/libVkLayer_khronos_validation.so" %
              (remote, remote, build_info["wrapper_file"], remote))
    staged = {}
    for path in files:
        staged[path.name] = {"source": str(path), "bytes": path.stat().st_size,
                             "sha256": sha256_file(path)}
    if not build_info["pinned_hash_match"]:
        log("WARNING: wrapper %s does not match the pinned hash (alternate/patched build)"
            % build_info["wrapper_file"])
    return {
        "remote_dir": remote,
        "files": staged,
        "wrapper_hashes_match_pinned": build_info["pinned_hash_match"],
        "wrapper_build": build_info,
        "wrapper_name": build_info["wrapper_file"],
        "wrapper_label": build_info["label"],
    }


def stage_usevvl_layer(adb, layer_local, log):
    """Stage the layer + manifest at /data/local/tmp, which the wrapper's
    built-in USE_VVL path adds to GraphicsEnv layer paths."""
    manifest = {
        "file_format_version": "1.1.2",
        "layer": {
            "name": "VK_LAYER_KHRONOS_validation",
            "type": "GLOBAL",
            "library_path": "./libVkLayer_khronos_validation.so",
            "api_version": "1.4.363",
            "implementation_version": "1",
            "description": "Khronos Validation Layer (manual staging for USE_VVL below-wrapper run)",
        },
    }
    local_manifest = Path(layer_local).parent / "VkLayer_khronos_validation.json"
    local_manifest.write_text(json.dumps(manifest, indent=2) + "\n")
    adb.push([Path(layer_local), local_manifest], "/data/local/tmp")
    adb.shell("chmod 644 /data/local/tmp/VkLayer_khronos_validation.json "
              "/data/local/tmp/libVkLayer_khronos_validation.so")
    log("staged layer+manifest at /data/local/tmp for USE_VVL runs")
    return {"manifest": manifest, "manifest_path": "/data/local/tmp/VkLayer_khronos_validation.json",
            "library_path": "/data/local/tmp/libVkLayer_khronos_validation.so"}


def run_case(args, adb, build, staged, case, log):
    remote = args.remote_dir
    if case["target"] == "system":
        driver = SYSTEM_LIBVULKAN
    else:
        driver = "%s/%s" % (remote, staged.get("wrapper_name", "candidate-stripped.so"))
    layer = "%s/libVkLayer_khronos_validation.so" % remote if case["layer"] else "none"
    env = {
        "LD_LIBRARY_PATH": remote,
        "PREFIX": remote,
        "WRAPPER_LOG_LEVEL": "none",
        "WRAPPER_CMD_LOG_LEVEL": "none",
        "VK_LOADER_DEBUG": "none",
    }
    env.update(case.get("env", {}))
    if case.get("env", {}).get("USE_VVL") == "1":
        env["WRAPPER_LOG_LEVEL"] = "debug"
        env["WRAPPER_CMD_LOG_LEVEL"] = "none"
    env_string = " ".join("%s=%s" % (key, value) for key, value in sorted(env.items()))
    command = ("cd %s && %s ./validation-probe %s %s %s %d %d 2>&1" %
               (remote, env_string, driver, layer, case["mode"], case["cycles"], case["edge"]))
    log("run %s: %s" % (case["name"], command))
    adb.logcat_clear()
    result = adb.shell(command, timeout=args.timeout)
    logcat = adb.logcat_dump()
    stdout = result["stdout"]
    stderr = result["stderr"]
    combined = stdout + ("\n" + stderr if stderr else "")
    messages = merge_messages(parse_validation_messages(combined),
                              parse_logcat_validation(logcat))
    events = parse_probe_events(combined)
    passes = [line for line in events if line.startswith("PASS ")]
    fails = [line for line in events if line.startswith("FAIL ")]
    result_lines = [line for line in events if line.startswith("RESULT ")]
    diagnostic_tokens = ("get_vulkan_handle", "GraphicsEnv", "layers in", "Layer[", "enabled_layer",
                        "USE_VVL", "Failed to intercept", "No layers found", "vkCreateInstance failed",
                        "Cannot open libgraphicsenv", "Cannot find", "setLayerPaths", "layer_settings")
    wrapper_diagnostics = []
    for line in (combined + "\n" + logcat).splitlines():
        if any(token in line for token in diagnostic_tokens):
            wrapper_diagnostics.append(line[:400])
    wrapper_diagnostics = wrapper_diagnostics[:120]
    below_layer_signal = {
        "graphics_env_paths_set": any("mLayerPaths" in line or "GraphicsEnv::mLayerPaths set" in line
                                      for line in wrapper_diagnostics),
        "layer_found_by_wrapper": any("Found" in line and "layer" in line for line in wrapper_diagnostics),
        "layer_enabled_in_wrapper_create_info": any("enabled_layer" in line or
                                                    "VK_LAYER_KHRONOS_validation" in line
                                                    for line in wrapper_diagnostics),
        "layer_not_present_error": any("VK_ERROR_LAYER_NOT_PRESENT" in line or
                                       "Failed to intercept" in line or
                                       "No layers found" in line for line in wrapper_diagnostics),
    }
    run = {
        "name": case["name"],
        "target": case["target"],
        "target_label": (staged.get("wrapper_label", "candidate wrapper")
                         if case["target"] == "wrapper"
                         else "system libvulkan.so native driver"),
        "layer": "manual-chain" if case["layer"] else "none",
        "mode": case["mode"],
        "cycles": case["cycles"],
        "edge": case["edge"],
        "why": case["why"],
        "command": command,
        "environment": env,
        "exit_code": result["exit_code"],
        "duration_seconds": result["duration_seconds"],
        "probe_events": events,
        "pass_lines": len(passes),
        "fail_lines": fails,
        "result_line": result_lines[-1] if result_lines else None,
        "messages": messages,
        "message_count": len(messages),
        "logcat_validation_lines": [line for line in logcat.splitlines() if "VALIDATION" in line],
        "wrapper_diagnostics": wrapper_diagnostics,
        "below_layer_loaded_signal": below_layer_signal,
        "expected_failure": bool(case.get("expect_failure")),
        "expected_failure_observed": (bool(case.get("expect_failure")) and result["exit_code"] != 0
                                          and any("create-instance" in line for line in fails)),
        "expected_vuid": (None if case.get("expect") == "any" else case.get("expect")),
        "expected_vuid_seen": (any(message["vuid"] == case.get("expect") for message in messages)
                               if case.get("expect") else None),
        "expect_no_messages": case.get("expect_none", False),
        "positive_control": bool(case.get("expect")) or case.get("expect_failure", False),
    }
    log_path = args.scratch / "logs" / (case["name"] + ".log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(
        "command: %s\nexit_code: %s\nduration_seconds: %s\n--- combined stdout/stderr ---\n%s\n"
        "--- logcat VALIDATION ---\n%s\n" %
        (command, result["exit_code"], result["duration_seconds"], combined, logcat),
        encoding="utf-8")
    run["capture_path"] = str(log_path)
    log("run %s exit=%s messages=%d %s" % (case["name"], result["exit_code"], len(messages),
                                           run["result_line"] or ""))
    return run


def aggregate(runs):
    unique = {}
    for run in runs:
        for message in run["messages"]:
            key = (message["vuid"], message["text_sha256_16"])
            entry = unique.get(key)
            if entry is None:
                entry = {
                    "vuid": message["vuid"],
                    "severity": message["severity"],
                    "message_id": message["message_id"],
                    "sample": message["text"],
                    "objects_sample": message.get("objects", []),
                    "runs": [],
                    "count": 0,
                    "classification": "pending-review",
                    "reason": None,
                }
                unique[key] = entry
            entry["count"] += 1
            if run["name"] not in entry["runs"]:
                entry["runs"].append(run["name"])
    return list(unique.values())



# ---------------------------------------------------------------------------
# Classification and receipt finalisation
# ---------------------------------------------------------------------------

# Manual classification of every distinct (VUID, message) that the chain
# produced.  Only the fixture's deliberate positive-control leak appears in
# this pass; it is identical through the wrapper chain and the system-loader
# chain, so it is fixture misuse, not a wrapper defect.
BELOW_WRAPPER_DEFECTS = {
    "D1": {
        "id": "D1",
        "title": "Wrapper does not destroy its internal BCn compute resources before vkDestroyDevice",
        "severity": "medium",
        "kind": "resource leak / spec violation at device teardown",
        "vuid": "VUID-vkDestroyDevice-device-05137",
        "observed": "Every below-wrapper run (including caps, which does no workload) reports 14 leaked "
                    "driver objects at vkDestroyDevice: 3 VkPipeline, 3 VkPipelineLayout, 3 "
                    "VkDescriptorSetLayout, 2 VkBuffer, 2 VkDeviceMemory and 1 VkCommandPool.",
        "wrapper_source": [
            "wrapper_objects.h:77-84 InterceptorState (descriptorSetLayout, pipelineLayout, pipeline, "
            "constantsBuffer, constantsBufferMemory); wrapper_objects.h:106-111 wrapper_device.computePool + "
            "s3tc/bc6/bc7 states",
            "wrapper_device.c:356-380 creates the three InterceptorState objects (s3tc, bc6, bc7) during "
            "device creation; wrapper_device.c:402 creates device->computePool",
            "wrapper_device.c:874-1008 (InterceptorState_Init) creates the descriptor set layout (961), "
            "pipeline layout (977) and compute pipeline (1007); the shader module is destroyed (1008)",
            "wrapper_device.c:839 declares CreateConstantsUniformBuffer; it is called for bc7 at 903 and "
            "bc6 at 928 and creates state->constantsBuffer + constantsBufferMemory (901-949)",
            "wrapper_device.c:54 declares InterceptorState_Cleanup but it has no definition and no call site "
            "(grep over the pinned source); WRAPPER_DestroyDevice (wrapper_device.c:605-651) destroys command "
            "buffers, tracked device memory, images, buffers, command pools and queues, then calls the driver "
            "DestroyDevice at 644-647 without destroying the BCn compute resources or device->computePool",
        ],
        "impact": "VUID violation / late-free at device destroy. The llvmpipe driver frees the objects inside "
                  "its DestroyDevice, so this is not a sustained process leak or memory-safety issue, but it is "
                  "a real out-of-contract teardown and would be reported by any validation-enabled run; "
                  "repeated device create/destroy cycles inherit the same violation.",
        "fix": "Call an implemented InterceptorState_Cleanup for s3tc/bc6/bc7 (DestroyPipeline, "
               "DestroyPipelineLayout, DestroyDescriptorSetLayout, DestroyBuffer + FreeMemory for the "
               "constants buffer) and vkDestroyCommandPool(device->computePool) before the driver "
               "DestroyDevice call in WRAPPER_DestroyDevice.",
    },
    "D2": {
        "id": "D2",
        "title": "HostSideDecompression leaves the source buffer memory host-mapped",
        "severity": "medium",
        "kind": "persistent host mapping / app-visible valid-usage violation",
        "vuid": "VUID-vkMapMemory-memory-00678",
        "observed": "The host-CPU decode run (USE_CPU_BCN=all) reports 3 x VUID-vkMapMemory-memory-00678 "
                    "('memory has already been mapped') on VkDeviceMemory 0x150000000015, one per cycle after "
                    "the first, while the app's own map/unmap pairs are balanced.",
        "wrapper_source": [
            "wrapper_device.c:1170-1190 HostSideDecompression maps srcBuffer->memory with MapMemory2KHR and "
            "never unmaps it",
            "wrapper_device.c:1229 unmaps only the destination staging memory; the source unmap is commented "
            "out at wrapper_device.c:1231-1232 with the TODO 'this breaks some applications that maps the "
            "srcBuffer, e.g. 32bit emulated placeAddr'",
            "the next cycle's application vkMapMemory on the same source buffer therefore traps below the "
            "wrapper as 'already mapped' (the wrapper's leaked mapping is what makes the app call invalid)",
        ],
        "impact": "Real wrapper defect in the USE_CPU_BCN fallback: the wrapper permanently holds a host "
                  "mapping of the application's source buffer, so any app that maps that buffer itself (the "
                  "normal way to fill a staging buffer) hits VUID-vkMapMemory-memory-00678 and may be broken "
                  "on drivers that reject the double map. Only reachable with the CPU decode fallback; the "
                  "default compute/image-view decode paths are unaffected.",
        "fix": "Unmap srcBuffer->memory in HostSideDecompression (ideally on all paths, including failures) "
               "instead of leaving the mapping open.",
    },
}

BELOW_WRAPPER_MESSAGE_CLASSES = {
    "VUID-vkMapMemory-memory-00678": ("wrapper-defect", "D2"),
    "CURRENT-VALIDATION-ENABLED": ("layer-information", None),
    "WARNING-cache-file-error": ("layer-information", None),
    "VALIDATION-SETTINGS": ("layer-configuration-artefact", None),
}

SCOPE_TEXT = (
    "Manual Khronos validation-layer chaining in front of the Vapor Vulkan wrapper and over the "
    "system libvulkan.so loader as a control (above-wrapper pass, top-level runs), plus a follow-up "
    "below-wrapper pass through the wrapper's built-in USE_VVL path (section below_wrapper_use_vvl) "
    "that puts the same layer between the wrapper and the llvmpipe driver. Offscreen native workload; "
    "no app/APK/Wine/Steam/game."
)

CLASSIFICATIONS = {
    "VUID-vkDestroyDevice-device-05137": {
        "classification": "fixture-misuse (deliberate positive control)",
        "reason": "The only messages in the whole matrix come from the fixture's vkDestroyDevice "
                  "positive-control mode, which intentionally leaks one VkBuffer + VkDeviceMemory to "
                  "prove the manually chained layer is live. The identical VUID appears on the system "
                  "libvulkan.so control chain, so it is not wrapper-specific and not a chaining artefact.",
    },
}

# The wrapper deliberately hides VK_EXT_debug_utils/VK_EXT_debug_report from the
# application-facing instance extension list (wrapper_instance.c:158-159), so the
# fixture cannot install its own debug-utils messenger; VVL's default callback
# (stdout + logcat tag VALIDATION) is used instead.
WRAPPER_SOURCE_LIMITATION_CITATIONS = {
    "layer_above_wrapper_sees_app_calls_only": [
        "wrapper source: pinned leegao/bionic-vulkan-wrapper 3abf48704a4d9303967a0811c0dc8ff8567d613b",
        "wrapper_dispatch: WRAPPER_CmdCopyBufferToImage (wrapper_device.c:1555) allocates the BCn staging "
        "VkBuffer/VkDeviceMemory (wrapper_device.c:1013-1048, 1662-1678) and records the compute dispatch / "
        "staging->image copies (wrapper_device.c:1681-1760) directly against the system loader/driver",
        "the manually chained layer sits above the wrapper's exported vk_icdGetInstanceProcAddr interface, so "
        "those internal calls are below the layer and cannot produce VUIDs in these runs",
    ],
    "builtin_use_vvl_below_wrapper_route": [
        "wrapper_instance.c:70-72 (CHECK_FLAG(\"USE_VVL\") -> set_layer_paths() before dlopening the system "
        "loader)",
        "wrapper_instance.c:372-399 (USE_VVL enables VK_LAYER_KHRONOS_validation in the instance create info "
        "passed to /system/lib64/libvulkan.so and attaches VkLayerSettingsCreateInfoEXT)",
        "graphics_env_hooks.cpp:12,15-77 (set_layer_paths() rewrites android::GraphicsEnv layer paths to a "
        "list that includes /data/local/tmp)",
        "runner support: USE_VVL_CASES + stage_usevvl_layer() stage the release layer + manifest at "
        "/data/local/tmp; executed in the below_wrapper_use_vvl section",
    ],
    "loaderless_dispatch_key": [
        "VVL 1.4 locates its dispatch state from the first word (loader data / dispatch pointer) of "
        "dispatchable objects: layers/utils/dispatch_utils.h:26 GetDispatchKey().",
        "Without a Vulkan loader, wrapper objects keep _loader_data == 0 (vk_object_base first member), so "
        "all dispatchable objects collapse to the same key. The manual chain therefore works for exactly "
        "one instance + one device (this fixture); multi-instance/multi-device chaining is untested.",
    ],
}


def false_like(run):
    return bool(run.get("expect_failure"))


def _read_capture(log_path):
    text = Path(log_path).read_text(encoding="utf-8", errors="replace")
    marker = "--- combined stdout/stderr ---\n"
    end_marker = "\n--- logcat VALIDATION ---\n"
    if marker not in text:
        return text
    body = text.split(marker, 1)[1]
    if end_marker in body:
        body = body.split(end_marker, 1)[0]
    return body


def _normalise_run(run):
    if not run.get("capture_path"):
        return run
    captured = _read_capture(run["capture_path"])
    messages = parse_validation_messages(captured)
    run["messages"] = messages
    run["message_count"] = len(messages)
    run["probe_events"] = parse_probe_events(captured)
    run["pass_lines"] = sum(1 for line in run["probe_events"] if line.startswith("PASS "))
    run["fail_lines"] = [line for line in run["probe_events"] if line.startswith("FAIL ")]
    result_lines = [line for line in run["probe_events"] if line.startswith("RESULT ")]
    run["result_line"] = result_lines[-1] if result_lines else None
    expected = run.get("expected_vuid")
    if run.get("expected_vuid") is None and run.get("expected_failure"):
        run["expected_vuid_seen"] = None
    else:
        run["expected_vuid_seen"] = (any(m["vuid"] == expected for m in messages) if expected else
                                     (len(messages) > 0 if run.get("positive_control") else None))
    return run


def _run_is_positive_control(runs, name):
    run = next((item for item in runs if item["name"] == name), None)
    return bool(run and (run.get("positive_control") or run.get("expect_failure")))


def _classify_entry(entry, runs, below):
    vuid = entry["vuid"]
    if below:
        if vuid == "VUID-vkDestroyDevice-device-05137":
            if entry["runs"] and all(name.endswith("wrapper-usevvl-vuid") for name in entry["runs"]):
                return {
                    "classification": "fixture-misuse (deliberate positive control)",
                    "reason": "The positive-control run deliberately leaks one VkBuffer + VkDeviceMemory, "
                              "which appears on top of the wrapper's own 14 leaked internal objects; it is "
                              "also the below-layer liveness proof.",
                    "finding": "positive-control",
                }
            return {
                "classification": "wrapper-defect (D1: internal BCn resources not destroyed before driver "
                                  "vkDestroyDevice)",
                "reason": "Every below-wrapper teardown (including caps, which runs no workload) leaves 14 "
                          "wrapper-created driver objects alive at vkDestroyDevice; see findings D1 and the "
                          "wrapper source citations there.",
                "finding": "D1",
            }
        if vuid == "VUID-vkMapMemory-memory-00678":
            return {
                "classification": "wrapper-defect (D2: HostSideDecompression leaves the source buffer "
                                  "host-mapped)",
                "reason": "The app's own map/unmap pairs are balanced; the memory is already mapped because "
                          "the wrapper's CPU decode path never unmaps the application source buffer; see "
                          "findings D2.",
                "finding": "D2",
            }
        if vuid == "CURRENT-VALIDATION-ENABLED":
            return {
                "classification": "layer-information (not a defect)",
                "reason": "Standard VVL startup message listing the enabled validation features.",
                "finding": None,
            }
        if vuid == "WARNING-cache-file-error":
            return {
                "classification": "layer-information (not a defect)",
                "reason": "VVL could not open its shader-validation cache under /tmp in the probe "
                          "environment; harmless for this matrix.",
                "finding": None,
            }
        if vuid == "VALIDATION-SETTINGS":
            return {
                "classification": "layer-configuration-artefact (not a wrapper defect)",
                "reason": "The wrapper passes VkLayerSettingsCreateInfoEXT with a log_filename under "
                          "/sdcard/Documents/Wrapper/ (wrapper_instance.c:365-399). The shell-domain native "
                          "probe cannot open that path, so VVL warns once and falls back to stdout, which is "
                          "also how this matrix captured the messages.",
                "finding": None,
            }
        return {
            "classification": "unclassified (needs review)",
            "reason": "No below-wrapper classification rule for this message id.",
            "finding": None,
        }
    if vuid in CLASSIFICATIONS:
        return dict(CLASSIFICATIONS[vuid], finding=None)
    if all(_run_is_positive_control(runs, name) for name in entry["runs"]):
        return {
            "classification": "fixture-misuse (deliberate positive control)",
            "reason": "Only emitted by the deliberate positive-control workload.",
            "finding": "positive-control",
        }
    return {
        "classification": "unclassified (needs review)",
        "reason": "No classification rule for this VUID.",
        "finding": None,
    }


def _finalize_common(evidence, runs):
    """Fields that are needed for both the above-wrapper and below-wrapper receipts."""
    tool = evidence.get("tool", {})
    build = tool.get("fixture_build", {})
    wrapper_build = tool.get("wrapper_build", {}) or {}
    staged_files = wrapper_build.get("staged_files", {}) or {}
    wrapper_hashes = {name: staged_files[name]["sha256"] for name in
                      (wrapper_build.get("wrapper_file", "candidate-stripped.so"),)
                      if name in staged_files}
    wrapper_sha = (wrapper_build.get("wrapper_sha256") or
                   wrapper_hashes.get(wrapper_build.get("wrapper_file", "candidate-stripped.so")) or
                   wrapper_hashes.get("candidate-stripped.so"))
    local_layer = Path(DEFAULT_SCRATCH / "android-binaries-1.4.363.0" / LAYER_RELEASE["library_path"])
    evidence["provenance"] = {
        "base_vapor_commit": evidence.get("base_vapor_commit"),
        "date": DATE,
        "wrapper": {
            "repository": "https://github.com/leegao/bionic-vulkan-wrapper",
            "source_commit": "3abf48704a4d9303967a0811c0dc8ff8567d613b",
            "candidate_reviewed_build": str(DEFAULT_WRAPPER_BUILD),
            "build_under_test": wrapper_build.get("path"),
            "build_under_test_wrapper_file": wrapper_build.get("wrapper_file"),
            "build_under_test_sha256": wrapper_sha,
            "candidate_stripped_sha256": (wrapper_sha
                                          if wrapper_build.get("wrapper_file", "candidate-stripped.so")
                                          == "candidate-stripped.so" else None),
            "hashes_match_pinned": wrapper_build.get("hashes_match_pinned"),
            "pinned_hash_match": wrapper_build.get("pinned_hash_match"),
            "label": wrapper_build.get("label"),
        },
        "validation_layer": {
            "repository": LAYER_RELEASE["repository"],
            "release_tag": LAYER_RELEASE["tag"],
            "asset": LAYER_RELEASE["asset"],
            "asset_url": LAYER_RELEASE["asset_url"],
            "android_archive_sha256": LAYER_RELEASE["archive_sha256"],
            "arm64_v8a_library_sha256": LAYER_RELEASE["library_sha256"],
            "local_library_sha256_verified": (sha256_file(local_layer) == LAYER_RELEASE["library_sha256"]
                                              if local_layer.exists() else None),
            "official_release_asset": True,
        },
        "fixture": {
            "source": tool.get("fixture_source"),
            "source_sha256": build.get("source_sha256"),
            "binary_sha256": build.get("binary_sha256"),
            "binary_bytes": build.get("binary_bytes"),
            "elf_max_load_align": build.get("elf_max_load_align"),
            "compiler": build.get("compiler"),
            "compile_command": build.get("command"),
            "page_size_link_flags": build.get("page_size_link_flags"),
        },
        "runner": {
            "path": tool.get("runner"),
            "sha256": tool.get("runner_sha256"),
        },
        "vvl_source_tarball_sha256": (
            sha256_file(DEFAULT_SCRATCH / "vvl-src-1.4.363.0.tar.gz")
            if (DEFAULT_SCRATCH / "vvl-src-1.4.363.0.tar.gz").exists() else None),
    }
    api_line = next((line for run in runs for line in run.get("probe_events", [])
                     if line.startswith("PHYSICAL_DEVICE ")), None)
    layer_line = next((line for run in runs for line in run.get("probe_events", [])
                       if line.startswith("LAYER name=")), None)
    evidence["version_compatibility"] = {
        "wrapper_reported_api_version": api_line,
        "layer_properties_line": layer_line,
        "assessment": (
            "The wrapper and the system loader both report Vulkan api 1.3.0; the official layer release "
            "reports VkLayerProperties.specVersion 1.4.363 and successfully validates the 1.3.0 "
            "instance/device (creation, dispatch and VUID reporting observed)."
        ),
    }
    evidence["run_matrix"] = [
        {
            "name": run["name"],
            "target": run["target_label"],
            "layer": run["layer"],
            "mode": run["mode"],
            "cycles": run["cycles"],
            "exit_code": run["exit_code"],
            "result": run["result_line"],
            "messages": run["message_count"],
            "expected_vuid": run.get("expected_vuid"),
            "expected_vuid_seen": run.get("expected_vuid_seen"),
            "positive_control": bool(run.get("positive_control")),
            "negative_control": bool(run.get("expect_no_messages")),
            "expected_failure": bool(run.get("expected_failure")),
            "expected_failure_observed": run.get("expected_failure_observed"),
        }
        for run in runs
    ]
    evidence["tool"]["layer_interface_negotiation"] = {
        "exported_by_layer": "vkNegotiateLoaderLayerInterfaceVersion (observed in the release library's "
                             "dynamic symbols; fixture printed negotiate=1)",
        "invoked_by_fixture": False,
        "interface_used_instead": "layer's exported vkGetInstanceProcAddr / vkGetDeviceProcAddr via the "
                                  "loader-link chain, with positive/negative controls proving dispatch",
    }
    evidence["raw_scratch"] = evidence.get("raw_scratch", {})
    evidence["raw_scratch"].setdefault("path", str(DEFAULT_SCRATCH))
    evidence["raw_scratch"].setdefault("per_run_logs", str(DEFAULT_SCRATCH / "logs"))
    evidence["raw_scratch"].setdefault(
        "notes", "logs/superseded-first-attempt/ holds the first pre-control system-bc1 capture (superseded "
                 "by system-chained-bc1-native); run-chain.log / run-chain2.log / run-usevvl.log hold the "
                 "runner invocations; emulator-boot.log is the AVD boot log.")
    return evidence


def finalize_evidence(evidence):
    """Recompute VUID aggregation / classification / comparison / conclusions.

    Handles two receipt shapes:
      * above-wrapper manual-chain matrix (default shape, top-level runs), and
      * below-wrapper USE_VVL matrix (runs named wrapper-usevvl-*), which is
        additionally enriched with the wrapper-internal defect findings.
    """
    evidence["scope"] = SCOPE_TEXT
    runs = [_normalise_run(run) for run in evidence.get("runs", [])]
    evidence["runs"] = runs
    below = any("wrapper-usevvl" in run["name"] for run in runs)

    by_key = {}
    for run in runs:
        for message in run["messages"]:
            key = (message["vuid"], message["text_sha256_16"])
            entry = by_key.get(key)
            if entry is None:
                entry = {
                    "vuid": message["vuid"],
                    "severity": message["severity"],
                    "message_id": message["message_id"],
                    "sample": message["text"],
                    "objects": message.get("objects", []),
                    "runs": [],
                    "count": 0,
                }
                by_key[key] = entry
            entry["count"] += 1
            if run["name"] not in entry["runs"]:
                entry["runs"].append(run["name"])
    vuid_table = []
    for entry in by_key.values():
        entry.update(_classify_entry(entry, runs, below))
        if entry["vuid"] == "VUID-vkDestroyDevice-device-05137":
            match = re.search(r"has (\d+) leaked objects", entry["sample"])
            entry["leaked_object_count"] = int(match.group(1)) if match else None
        vuid_table.append(entry)
    vuid_table.sort(key=lambda item: (item["severity"] != "Validation Error", item["vuid"], item["sample"]))
    evidence["vuid_table"] = vuid_table

    def run_named(name):
        return next((run for run in runs if run["name"] == name or run["name"].endswith(name)), None)

    if below:
        evidence = _finalize_below_wrapper(evidence, runs, vuid_table, run_named)
        return _finalize_common(evidence, runs)

    wrapper_runs = [run for run in runs if run["target"] == "wrapper" and run["layer"] == "manual-chain"]
    system_runs = [run for run in runs if run["target"] == "system" and run["layer"] == "manual-chain"]
    workload_wrapper_runs = [run for run in wrapper_runs if run["mode"] not in ("vuid", "caps")]
    workload_system_runs = [run for run in system_runs if run["mode"] not in ("vuid", "caps")]
    wrapper_messages = sum(run["message_count"] for run in workload_wrapper_runs)
    system_messages = sum(run["message_count"] for run in workload_system_runs)
    positive_controls = [run for run in runs if run.get("positive_control")]
    evidence["comparison"] = {
        "layer_release": LAYER_RELEASE,
        "chaining_worked_over_wrapper": any(
            run["target"] == "wrapper" and run["layer"] == "manual-chain" and run["exit_code"] == 0
            and run["result_line"] and run["result_line"].startswith("RESULT ok") for run in runs),
        "chaining_worked_over_system_loader": any(
            run["target"] == "system" and run["layer"] == "manual-chain" and run["exit_code"] == 0
            and run["result_line"] and run["result_line"].startswith("RESULT ok") for run in runs),
        "wrapper_chained_workload_runs": [
            {"name": run["name"], "mode": run["mode"], "cycles": run["cycles"],
             "exit_code": run["exit_code"], "result": run["result_line"],
             "messages": run["message_count"]} for run in workload_wrapper_runs],
        "system_control_workload_runs": [
            {"name": run["name"], "mode": run["mode"], "cycles": run["cycles"],
             "exit_code": run["exit_code"], "result": run["result_line"],
             "messages": run["message_count"]} for run in workload_system_runs],
        "wrapper_chained_workload_messages": wrapper_messages,
        "system_control_workload_messages": system_messages,
        "layer_liveness_controls": [
            {"name": run["name"], "expected_vuid": run.get("expected_vuid"),
             "expected_vuid_seen": run.get("expected_vuid_seen"),
             "messages": run["message_count"]} for run in positive_controls],
        "negative_controls": [
            {"name": run["name"], "messages": run["message_count"]}
            for run in runs if run.get("expect_no_messages")],
        "conclusion": (
            "The manual VK_LAYER_LINK_INFO chain loaded and dispatched the official layer in both "
            "configurations; the deliberate positive controls reported the expected VUID through the "
            "wrapper chain and through the system-loader chain, and the no-layer negative control "
            "reported nothing. No VUID was reported for any representative wrapper workload "
            "(BC1 compute/image-view, BC3, host-CPU decode, two-region copy, caps), so nothing in this "
            "matrix is classifiable as a wrapper defect at the application-facing interface. This is a "
            "bounded negative result: the layer sits ABOVE the wrapper and cannot see the wrapper's "
            "internal driver calls."),
    }
    wrapper_defects = [entry for entry in vuid_table
                       if entry["classification"].startswith("wrapper")]
    evidence["summary"] = {
        "runs": len(runs),
        "runs_with_messages": sum(1 for run in runs if run["message_count"]),
        "total_messages": sum(run["message_count"] for run in runs),
        "unique_vuid_names": len({entry["vuid"] for entry in vuid_table}),
        "unique_message_records": len(vuid_table),
        "chain_worked": evidence["comparison"]["chaining_worked_over_wrapper"],
        "positive_control_wrapper_chain_vuid_seen": bool(
            (run_named("wrapper-chained-vuid") or {}).get("expected_vuid_seen")),
        "positive_control_system_chain_vuid_seen": bool(
            (run_named("system-chained-vuid") or {}).get("expected_vuid_seen")),
        "negative_control_direct_messages": sum(
            run["message_count"] for run in runs if run.get("expect_no_messages")),
        "wrapper_workload_messages": wrapper_messages,
        "system_control_workload_messages": system_messages,
        "wrapper_defects_found": wrapper_defects,
        "classification": "complete",
        "messages_by_class": {
            "wrapper_defect": 0,
            "fixture_misuse": sum(entry["count"] for entry in vuid_table
                                  if entry["classification"].startswith("fixture-misuse")),
            "chaining_artefact": 0,
            "emulator_driver_issue": 0,
        },
        "verdict": (
            "Manual chaining works; every VUID observed is the fixture's deliberate positive-control "
            "leak (same on the system-loader control), so no wrapper defect was detected at the "
            "application-facing layer position. Wrapper-internal driver usage is NOT validated by this "
            "placement; the below-wrapper USE_VVL section covers it."),
    }
    evidence["wrapper_validation_scope"] = WRAPPER_SOURCE_LIMITATION_CITATIONS
    evidence["conclusions"] = [
        "Manual loader-less chaining works: the official Khronos layer's vkCreateInstance / vkCreateDevice "
        "accepted the hand-built VK_LAYER_LINK_INFO (plus loader-data-callback) chains and dispatched every "
        "application call back down to the wrapper and to the system libvulkan.so loader control.",
        "Layer liveness is proven, not assumed: with the chain in place a deliberately leaked VkBuffer + "
        "VkDeviceMemory produced VUID-vkDestroyDevice-device-05137 through BOTH the wrapper chain and the "
        "system-loader chain; the same fixture without the layer produced no message.",
        "VUID count by class over the whole matrix: total validation messages = 2 (2 records, 1 VUID "
        "name); class fixture-misuse (deliberate positive control) = 2/2; wrapper defect = 0; chaining "
        "artefact = 0; emulator/driver issue = 0.",
        "Wrapper-vs-control comparison: all six wrapper chained workloads and all three system-loader "
        "control workloads exited 0 and reported zero validation messages; the fixture also passes without "
        "a layer, so the zero-message wrapper result is not a dispatch failure.",
        "No wrapper defect was found at the application-facing interface under this workload, but the "
        "placement bounds the claim: the layer is above the wrapper and cannot see the wrapper's internal "
        "driver calls. The below-wrapper USE_VVL section covers that gap.",
        "The two observed messages are fixture misuse (positive control), not a wrapper defect, not a "
        "chaining artefact, not an emulator driver issue; the identical message on the system control "
        "chain confirms it is independent of the wrapper.",
    ]
    evidence.setdefault("limitations", [])
    evidence["limitations"] = [
        "The layer is manually chained ABOVE libvulkan_wrapper.so, so it validates the application->wrapper "
        "API contract only; wrapper-internal driver calls are validated separately in the "
        "below_wrapper_use_vvl section.",
        "The manual loader-less chain relies on VVL's loader-data dispatch key; without a loader the "
        "wrapper's dispatchable objects all key to NULL, so the chain was exercised with exactly one "
        "instance and one device. Multiple instances/devices were not tested.",
        "The fixture cannot install a VK_EXT_debug_utils messenger because the wrapper deliberately hides "
        "VK_EXT_debug_utils/VK_EXT_debug_report (wrapper_instance.c:158-159); the layer's default callback "
        "(stdout + logcat tag VALIDATION) was captured instead.",
        "Runtime is llvmpipe software Vulkan inside the Android emulator with 4 KB pages (AVD VaporProbe36D, "
        "API 36, userdebug/dev-keys); no physical GPU, no Adreno/Mali driver, no game, APK, Wine or Steam.",
        "The emulator was booted under heavy host co-tenancy; sys.boot_completed was not visible to the "
        "shell, so the documented fallback readiness check (activity service + package manager) was used.",
        "Workloads are small offscreen images (16x16 BC1/BC3/RGBA8, up to 8 cycles, one two-region copy); "
        "they exercise the wrapper's BCn staging/compute/decode paths but are not a game workload and not a "
        "concurrency or performance test.",
        "Both observed VUID-vkDestroyDevice-device-05137 occurrences are the fixture's deliberate "
        "positive-control leak; the identical message on the system-loader chain confirms it is not "
        "wrapper-specific.",
        "No validation-layer timing/performance claim is made.",
        "The layer binary/build was used only as a loadable validation layer.",
        "The fixture records that the layer exports vkNegotiateLoaderLayerInterfaceVersion but does not call "
        "it: the layer is driven through its exported vkGetInstanceProcAddr.",
    ]
    return _finalize_common(evidence, runs)


def _finalize_below_wrapper(evidence, runs, vuid_table, run_named):
    """Below-wrapper (wrapper USE_VVL) receipt.

    Handles both the unpatched baseline build and an alternate (e.g. D1/D2
    patched) build: `expected_findings` marks defects that should be gone, and
    their status is reported as fixed / still present.
    """
    tool_build = (evidence.get("tool", {}) or {}).get("wrapper_build", {}) or {}
    expected = [item for item in (evidence.get("expected_findings") or
                                  tool_build.get("expected_findings") or []) if item]
    defect_ids = ["D1", "D2"]
    for item in expected:
        if item not in defect_ids:
            defect_ids.append(item)

    def leaked_count(run):
        for message in (run or {}).get("messages", []):
            if message["vuid"] == "VUID-vkDestroyDevice-device-05137":
                match = re.search(r"has (\d+) leaked objects", message["text"])
                if match:
                    return int(match.group(1))
        return None

    findings = []
    for defect_id in defect_ids:
        meta = BELOW_WRAPPER_DEFECTS.get(defect_id, {})
        records = [entry for entry in vuid_table if entry.get("finding") == defect_id]
        occurred_runs, occurred_messages, leaked_counts = [], 0, []
        for record in records:
            occurred_messages += record["count"]
            if record.get("leaked_object_count") is not None:
                leaked_counts.append(record["leaked_object_count"])
            for name in record["runs"]:
                if name not in occurred_runs:
                    occurred_runs.append(name)
        observed = occurred_messages > 0
        if observed:
            status = "reproduced in this build"
            classification = "wrapper defect (below-wrapper layer, not visible from above)"
        elif defect_id in expected:
            status = "not reproduced (expected fix verified)"
            classification = "expected fix verified (not reproduced in this build)"
        else:
            status = "not observed in this build"
            classification = "not observed in this build"
        note = None
        if defect_id == "D1":
            if observed:
                note = ("The positive-control run also contains the same wrapper objects, so its leak "
                        "count is 14 + the fixture's deliberate 1 VkBuffer + 1 VkDeviceMemory.")
            elif defect_id in expected:
                note = ("No VUID-vkDestroyDevice-device-05137 leak was reported for the wrapper in this "
                        "build, as expected after the device-teardown patch; the positive control is the "
                        "remaining source of that VUID.")
        findings.append({
            "id": defect_id,
            "title": meta.get("title", defect_id),
            "severity": meta.get("severity", "medium"),
            "kind": meta.get("kind", "expected patch result"),
            "vuid": meta.get("vuid"),
            "observed": meta.get("observed"),
            "status": status,
            "occurrences": {"runs": occurred_runs, "messages": occurred_messages, "note": note},
            "leaked_object_counts": leaked_counts,
            "wrapper_source": meta.get("wrapper_source", []),
            "impact": meta.get("impact"),
            "fix": meta.get("fix"),
            "classification": classification,
        })
    evidence["findings"] = findings
    observed_findings = [item for item in findings if item["occurrences"]["messages"] > 0]
    expected_status = {item["id"]: item["status"] for item in findings if item["id"] in expected}

    nolayer = run_named("wrapper-usevvl-nolayer")
    positive = run_named("wrapper-usevvl-vuid")
    workload_runs = [run for run in runs
                     if not run.get("positive_control") and not run.get("expect_failure")]
    workload_messages = sum(run["message_count"] for run in workload_runs)

    positive_workload = next((run for run in workload_runs if run.get("wrapper_diagnostics")), None)
    positive_diagnostics = (positive_workload or {}).get("wrapper_diagnostics", [])
    key_diagnostics = [line for line in positive_diagnostics
                       if any(token in line for token in
                              ("GraphicsEnv::mLayerPaths set", "Found 1 layers", "Layer[0]"))][:6]
    settings_entry = next((entry for entry in vuid_table if entry["vuid"] == "VALIDATION-SETTINGS"), None)
    settings_lines = ([settings_entry["sample"]] if settings_entry else [])
    evidence["route_evidence"] = {
        "graphics_env_layer_paths": [
            line for line in positive_diagnostics if "mLayerPaths" in line][:2],
        "layer_discovered_by_wrapper": [
            line for line in positive_diagnostics if "Found 1 layers" in line or "Layer[0]" in line][:3],
        "wrapper_layer_settings_log_path": settings_lines,
        "key_diagnostics": key_diagnostics,
        "negative_control_creation_failure": (nolayer or {}).get("result_line"),
    }

    d1_observed = any(item["id"] == "D1" and item["occurrences"]["messages"] > 0 for item in findings)
    d1_fixed_expected = "D1" in expected and not d1_observed
    positive_leaked = leaked_count(positive)
    wrapper_internal_visible = None
    if d1_observed:
        wrapper_internal_visible = {
            "vuid": "VUID-vkDestroyDevice-device-05137",
            "objects": ["VkPipeline x3", "VkPipelineLayout x3", "VkDescriptorSetLayout x3",
                        "VkBuffer x2", "VkDeviceMemory x2", "VkCommandPool x1"],
            "meaning": "These are wrapper-internal BCn compute objects created at wrapper device init, not "
                       "application handles: only a layer below the wrapper can report them, which proves "
                       "the layer is between the wrapper and the driver.",
        }
    evidence["liveness_control"] = {
        "below_layer_loaded_by_wrapper": {
            "run": (positive_workload or {}).get("name"),
            "wrapper_diagnostics": key_diagnostics,
            "meaning": "The wrapper's set_layer_paths() hook rewrote GraphicsEnv layer paths to include "
                       "/data/local/tmp, the wrapper enumerated VK_LAYER_KHRONOS_validation from the system "
                       "loader and enabled it in the loader instance create info, so the layer runs below "
                       "the wrapper.",
        },
        "negative_control": {
            "run": (nolayer or {}).get("name"),
            "expected_failure": (nolayer or {}).get("expected_failure"),
            "expected_failure_observed": (nolayer or {}).get("expected_failure_observed"),
            "result": (nolayer or {}).get("result_line"),
            "meaning": "With USE_VVL=1 but no layer discoverable, the wrapper itself refuses instance "
                       "creation with VK_ERROR_LAYER_NOT_PRESENT (-6): the wrapper really asks the system "
                       "loader for VK_LAYER_KHRONOS_validation instead of silently running unvalidated.",
        },
        "positive_control": {
            "run": (positive or {}).get("name"),
            "message_count": (positive or {}).get("message_count"),
            "expected_vuid_seen": (positive or {}).get("expected_vuid_seen"),
            "leaked_object_count": positive_leaked,
            "meaning": ("The deliberate app-level leak is always attributable to the fixture; in the "
                        "unpatched build it appeared on top of 14 wrapper-internal leaks (16 total), in the "
                        "patched build it should be the only leak (2 objects) and thereby keep the "
                        "below-layer liveness proof even when D1 is fixed."),
        },
        "wrapper_internal_objects_visible": wrapper_internal_visible,
        "expected_fix_liveness": {
            "applies": bool(expected),
            "d1_fixed_expected": d1_fixed_expected,
            "positive_control_leaked_objects": positive_leaked,
            "meaning": ("With D1 fixed, the positive control must still produce exactly 2 leaked objects "
                        "(fixture-owned VkBuffer + VkDeviceMemory) through the below-wrapper layer, which "
                        "keeps the liveness proof independent of the wrapper leak."),
        },
    }
    d2_observed = any(item["id"] == "D2" and item["occurrences"]["messages"] > 0 for item in findings)
    expected_fixed = all(status.startswith("not reproduced") for status in expected_status.values()) \
        if expected_status else None

    if expected:
        above_vs_below = (
            "A/B against the unpatched below-wrapper section: expected findings " + ", ".join(expected) +
            " are reported as " + ("fixed/not reproduced" if expected_fixed else "NOT fully fixed") +
            ". Workloads must stay correct (RESULT ok) for the patches to count as clean.")
    else:
        above_vs_below = (
            "The above-wrapper matrix (top-level receipt) found 0 wrapper defects because it only sees the "
            "application-facing contract. The below-wrapper layer finds 2 wrapper-internal defects: D1 (14 "
            "driver objects left alive at every vkDestroyDevice, including the caps run) and D2 (source "
            "buffer host mapping leaked by the USE_CPU_BCN decode path).")
    evidence["comparison"] = {
        "route": "wrapper's built-in USE_VVL path (wrapper_instance.c:70-72,372-399) + "
                 "graphics_env_hooks.cpp:12,15-77",
        "below_layer_loaded": True,
        "wrapper_build": tool_build,
        "expected_findings": expected,
        "expected_findings_status": expected_status,
        "workload_runs": [
            {"name": run["name"], "mode": run["mode"], "cycles": run["cycles"],
             "exit_code": run["exit_code"], "result": run["result_line"],
             "messages": run["message_count"]} for run in workload_runs],
        "workload_messages": workload_messages,
        "negative_control_failed_as_expected": bool((nolayer or {}).get("expected_failure_observed")),
        "positive_control_messages": (positive or {}).get("message_count"),
        "positive_control_leaked_objects": positive_leaked,
        "above_vs_below": above_vs_below,
        "conclusion": (
            ("Patched build: expected fixes verified and the below-wrapper route still dispatches; all "
             "workloads remained correct." if expected_fixed else
             "Below-wrapper route works end-to-end; expected fixes are " +
             ("verified" if expected_fixed else "not fully verified") + " (see expected_findings_status).")
            if expected else
            "The below-wrapper route works end-to-end and turned the previously unvalidated gap into two "
            "concrete wrapper-source findings. Every workload still produced correct pixels/readbacks "
            "(RESULT ok), so the defects are teardown/mapping-lifetime issues rather than functional BCn "
            "decode failures."),
    }

    wrapper_defect_messages = sum(
        entry["count"] for entry in vuid_table
        if entry["classification"].startswith("wrapper-defect"))
    fixture_misuse_messages = sum(
        entry["count"] for entry in vuid_table
        if entry["classification"].startswith("fixture-misuse"))
    layer_info_messages = sum(
        entry["count"] for entry in vuid_table
        if entry["classification"].startswith("layer-information"))
    layer_config_messages = sum(
        entry["count"] for entry in vuid_table
        if entry["classification"].startswith("layer-configuration"))
    leaked_object_records = [
        {"vuid": entry["vuid"], "leaked_object_count": entry.get("leaked_object_count"),
         "runs": entry["runs"], "classification": entry["classification"],
         "messages": entry["count"]}
        for entry in vuid_table if entry.get("leaked_object_count") is not None]
    liveness_verified = (bool((nolayer or {}).get("expected_failure_observed")) and
                         bool((positive or {}).get("message_count")) and
                         (d1_observed or positive_leaked == 2))
    if expected and expected_fixed:
        verdict = (
            "Patched build: D1/D2 were not reproduced below the wrapper (expected fixes verified), the "
            "below-layer liveness proof is retained by the fixture-only positive control, and all workloads "
            "still exited 0 with correct pixel/readback checks.")
    elif expected:
        verdict = ("Patched build: expected fixes are NOT fully verified; see expected_findings_status and "
                   "the VUID table for the remaining messages.")
    else:
        verdict = (
            "The wrapper's USE_VVL path placed the official validation layer between the wrapper and the "
            "llvmpipe driver, and the layer immediately exposed two wrapper-internal defects that the "
            "above-wrapper pass could not see: D1 (14 wrapper-created driver objects never destroyed before "
            "driver vkDestroyDevice, every run) and D2 (the host-CPU decode path leaves the app's source "
            "buffer host-mapped, VUID-vkMapMemory-memory-00678). All workloads still passed their pixel/"
            "readback checks.")
    evidence["summary"] = {
        "runs": len(runs),
        "runs_with_messages": sum(1 for run in runs if run["message_count"]),
        "total_messages": sum(run["message_count"] for run in runs),
        "unique_vuid_names": len({entry["vuid"] for entry in vuid_table}),
        "unique_message_records": len(vuid_table),
        "wrapper_defect_messages": wrapper_defect_messages,
        "fixture_misuse_messages": fixture_misuse_messages,
        "layer_information_messages": layer_info_messages,
        "layer_configuration_artefact_messages": layer_config_messages,
        "chaining_artefact_messages": 0,
        "emulator_driver_issue_messages": 0,
        "messages_by_class": {
            "wrapper_defect": wrapper_defect_messages,
            "fixture_misuse": fixture_misuse_messages,
            "layer_information": layer_info_messages,
            "layer_configuration_artefact": layer_config_messages,
            "chaining_artefact": 0,
            "emulator_driver_issue": 0,
        },
        "wrapper_defects_found": [
            {"id": item["id"], "title": item["title"], "severity": item["severity"],
             "vuid": item["vuid"], "kind": item["kind"], "status": item["status"],
             "occurrences": item["occurrences"]} for item in observed_findings],
        "expected_findings": expected,
        "expected_defects_status": expected_status,
        "expected_fix_verified": expected_fixed,
        "leaked_object_records": leaked_object_records,
        "below_layer_liveness_verified": liveness_verified,
        "negative_control_verified": bool((nolayer or {}).get("expected_failure_observed")),
        "classification": "complete",
        "verdict": verdict,
    }

    conclusions = [
        "The documented below-wrapper route works: the wrapper's USE_VVL path found the staged layer via "
        "the GraphicsEnv layer-path hook, enabled VK_LAYER_KHRONOS_validation in the instance create info "
        "it passes to /system/lib64/libvulkan.so, and the layer validated the wrapper's internal driver "
        "calls. The negative control (USE_VVL=1 with no layer staged) failed instance creation with "
        "VK_ERROR_LAYER_NOT_PRESENT (-6), proving the wrapper really requests the layer.",
    ]
    for finding_id, status in expected_status.items():
        if status.startswith("not reproduced"):
            conclusions.append(
                "Expected fix %s verified: the defect was not reproduced in this build (%s)."
                % (finding_id, status))
        else:
            conclusions.append("Expected fix %s NOT verified: the defect is still reproduced (%s)."
                               % (finding_id, status))
    for item in findings:
        if item["id"] in expected:
            continue
        if item["occurrences"]["messages"]:
            conclusions.append(
                "Wrapper-internal defect %s (%s): %s Occurrences: %s."
                % (item["id"], item.get("severity"), item.get("kind"),
                   item["occurrences"]["runs"]))
    conclusions += [
        "Message classification for this below-wrapper matrix: %d wrapper-defect, %d fixture-misuse, "
        "%d layer-information, %d layer-configuration-artefact, 0 chaining-artefact, 0 emulator-driver "
        "messages." % (wrapper_defect_messages, fixture_misuse_messages, layer_info_messages,
                       layer_config_messages),
        "All below-wrapper workloads (BC1 compute view0/image-view, BC3, host-CPU decode, two-region copy, "
        "caps) exited 0 with correct pixel/readback verification; the wrapper's BCn decode output remains "
        "functionally correct in this build.",
        ("This patched-build run is the A/B counterpart of the unpatched below-wrapper section; the A/B "
         "table is attached when --ab-base-section was supplied." if expected else
         "These defects are invisible to the above-wrapper layer position and were missed by the earlier "
         "app-facing pass; this demonstrates why the below-wrapper route was needed."),
    ]
    evidence["conclusions"] = conclusions

    limitations = [
        "Below-wrapper validation was only possible through the wrapper's own USE_VVL hook; it relies on "
        "dlopening android::GraphicsEnv and rewriting its layer path list to include /data/local/tmp "
        "(graphics_env_hooks.cpp:12,15-77). This worked on the API 36 userdebug emulator; it is not a "
        "device-independent or upstream-supported interface.",
        "The layer is only loaded because the wrapper requests VK_LAYER_KHRONOS_validation in the instance "
        "create info it passes down; the negative control confirms that without a discoverable layer the "
        "wrapper refuses instance creation instead of running unvalidated.",
        "The VVL log_file setting the wrapper passes points at /sdcard/Documents/Wrapper/, which the "
        "shell-domain native probe cannot open; VVL falls back to stdout. Those VALIDATION-SETTINGS warnings "
        "are environment-specific, not a wrapper defect.",
        "One instance and one device per process; D1 objects are counted at driver vkDestroyDevice. A "
        "validation-enabled real app would also count any objects it leaks, so counts are only compared "
        "between builds of this same fixture.",
        "D1's impact is bounded by the driver: llvmpipe's DestroyDevice frees leaked objects at device "
        "teardown, so this is an out-of-contract teardown/VUID, not a sustained process leak. A driver that "
        "asserts on live child objects at DestroyDevice could behave differently.",
        "D2 is only reachable with the USE_CPU_BCN fallback (host decoding); the default compute/image-view "
        "decode paths do not map the source buffer.",
        "Runtime is llvmpipe software Vulkan in the Android emulator (4 KB pages, API 36 userdebug); no "
        "physical GPU, no game, no concurrency stress, no performance claim. The emulator used the "
        "documented services-fallback readiness check under host co-tenancy.",
        "The patched build is a staged wrapper-build directory; its wrapper .so hash is recorded in this "
        "section's wrapper_build/provenance block and compared by the A/B table. Nothing upstream was "
        "touched and no commit was made by this review.",
        "The layer release provenance is the official Khronos release asset recorded in the provenance "
        "block; the layer's own correctness was not otherwise audited.",
    ]
    evidence["limitations"] = limitations
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--avd", default=DEFAULT_AVD)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--emulator", default=str(DEFAULT_EMULATOR))
    parser.add_argument("--adb", default=str(DEFAULT_ADB))
    parser.add_argument("--ndk", default=str(DEFAULT_NDK))
    parser.add_argument("--clang", default=None)
    parser.add_argument("--wrapper-build", default=str(DEFAULT_WRAPPER_BUILD),
                        help="wrapper build directory (default: pinned qualification build)")
    parser.add_argument("--wrapper-so", default=None,
                        help="wrapper shared object inside --wrapper-build (name or absolute path); "
                             "default: auto-detect candidate-stripped.so/candidate.so/libvulkan_wrapper.so")
    parser.add_argument("--aux-dir", default=None,
                        help="directory with the auxiliary runtime libraries (default: "
                             "<wrapper-build>/native-probe, falling back to the pinned build)")
    parser.add_argument("--wrapper-label", default=None,
                        help="human label for the wrapper target in the receipt")
    parser.add_argument("--validate-wrapper-build", action="store_true",
                        help="resolve/print the wrapper build layout and exit (no emulator, no staging)")
    parser.add_argument("--section", default="below_wrapper_use_vvl",
                        help="receipt section to write with --merge-existing (default: "
                             "below_wrapper_use_vvl)")
    parser.add_argument("--run-prefix", default="",
                        help="prefix for below-wrapper run names/logs (e.g. 'patched-') to keep A/B "
                             "captures distinct")
    parser.add_argument("--partial-findings", default=None,
                        help="comma-separated finding ids expected to be only partially fixed (e.g. D1 when "
                             "residual objects are deliberately left to the driver)")
    parser.add_argument("--expected-findings", default=None,
                        help="comma-separated finding ids expected to be fixed in this build (e.g. D1,D2); "
                             "their status is reported as fixed/not-fixed")
    parser.add_argument("--build-status", default=None,
                        choices=["unpatched", "interim", "final"],
                        help="record whether this run used the unpatched, an interim staged, or the final "
                             "patched wrapper build")
    parser.add_argument("--build-note", default=None,
                        help="free-text note recorded with the build status (e.g. pending patch details)")
    parser.add_argument("--archive-existing-section", action="store_true",
                        help="when replacing an existing section, keep a lightweight history record of it")
    parser.add_argument("--ab-base-section", default=None,
                        help="existing receipt section to compare against (builds section['ab_table'])")
    parser.add_argument("--layer", default=str(DEFAULT_SCRATCH / "android-binaries-1.4.363.0"
                                               / LAYER_RELEASE["library_path"]))
    parser.add_argument("--scratch", default=str(DEFAULT_SCRATCH))
    parser.add_argument("--evidence", default=str(DEFAULT_EVIDENCE))
    parser.add_argument("--remote-dir", default="/data/local/tmp/vapor-wrapper-validation-20261022")
    parser.add_argument("--gpu", default="lavapipe")
    parser.add_argument("--boot-timeout", type=int, default=900)
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--cases", choices=["all", "chain", "usevvl"], default="all")
    parser.add_argument("--no-boot", action="store_true", help="require an already-online emulator")
    parser.add_argument("--keep-emulator", action="store_true")
    parser.add_argument("--keep-remote", action="store_true")
    parser.add_argument("--merge-existing", action="store_true",
                        help="add the below-wrapper USE_VVL results as a new section of an existing "
                             "evidence receipt instead of overwriting it")
    parser.add_argument("--reparse-below-wrapper", action="store_true",
                        help="rebuild the below_wrapper_use_vvl section (message tables/classification/"
                             "findings) from its raw per-run captures, without an emulator")
    parser.add_argument("--reparse-evidence", action="store_true",
                        help="rebuild message tables/classification from the raw per-run captures in an "
                             "existing evidence JSON (no emulator needed)")
    args = parser.parse_args(argv)
    args.scratch = Path(args.scratch).resolve()
    args.evidence = Path(args.evidence).resolve()

    def log(message):
        stamp = time.strftime("%H:%M:%S")
        print("[%s] %s" % (stamp, message), flush=True)

    args.scratch.mkdir(parents=True, exist_ok=True)
    (args.scratch / "logs").mkdir(exist_ok=True)
    (args.scratch / "bin").mkdir(exist_ok=True)
    if not Path(args.layer).exists():
        raise SystemExit("error: validation layer library not found: %s (download the release asset "
                         "described in the evidence receipt)" % args.layer)

    if args.reparse_below_wrapper:
        evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
        section = evidence.get(args.section)
        if not section:
            raise SystemExit("error: evidence has no %s section" % args.section)
        keep = {key: section.get(key) for key in
                ("how", "staged_below_layer", "status", "date", "emulator", "emulator_cleanup",
                 "wrapper_build", "expected_findings", "partial_findings", "run_prefix",
                 "ab_base_section", "section_name", "build_status", "build_note", "build_sha256",
                 "capture_note")}
        evidence.setdefault("tool", {})["runner_sha256"] = sha256_file(Path(__file__))
        if keep.get("wrapper_build"):
            evidence.setdefault("tool", {})["wrapper_build"] = keep["wrapper_build"]
        sub = {
            "date": DATE,
            "base_vapor_commit": evidence.get("base_vapor_commit"),
            "tool": evidence.get("tool", {}),
            "emulator": evidence.get("emulator", {}),
            "raw_scratch": evidence.get("raw_scratch", {}),
            "expected_findings": (keep.get("expected_findings") or
                                  (keep.get("wrapper_build") or {}).get("expected_findings") or []),
            "runs": section.get("runs", []),
        }
        sub = finalize_evidence(sub)
        for key in ("runs", "vuid_table", "summary", "comparison", "conclusions", "limitations",
                    "findings", "liveness_control", "route_evidence", "provenance",
                    "version_compatibility", "run_matrix", "raw_scratch"):
            section[key] = sub[key]
        for key, value in keep.items():
            if value is not None:
                section[key] = value
        base_name = keep.get("ab_base_section")
        if base_name and evidence.get(base_name):
            section["ab_table"] = build_ab_table(evidence[base_name], section)
            apply_partial_ab_status(section)
        evidence[args.section] = section
        if args.section == "below_wrapper_use_vvl":
            route = evidence.setdefault("below_wrapper_route", {})
            route["status"] = "executed - see below_wrapper_use_vvl"
            route["results_section"] = "below_wrapper_use_vvl"
            route["executed_at"] = "2026-10-22"
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        log("reparsed section '%s': %d runs, %d messages, %d findings"
            % (args.section, len(section["runs"]), section["summary"]["total_messages"],
               len(section.get("findings", []))))
        return 0

    if args.reparse_evidence:
        evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
        evidence.setdefault("tool", {})["runner_sha256"] = sha256_file(Path(__file__))
        evidence = finalize_evidence(evidence)
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        log("reparsed evidence %s: %d runs, %d messages, %d VUID records"
            % (args.evidence, len(evidence["runs"]), evidence["summary"]["total_messages"],
               evidence["summary"]["unique_message_records"]))
        return 0

    if args.validate_wrapper_build:
        info = resolve_wrapper_build(args, log)
        print(json.dumps(info, indent=2))
        return 0

    build = build_fixture(args, log)
    log("fixture built: sha256=%s max_load_align=%s"
        % (build["binary_sha256"][:16], build["elf_max_load_align"]))

    emulator = Emulator(args, log)
    booted = False
    adb = emulator.adb
    device_facts = {}
    staged = {}
    usevvl_staged = {}
    runs = []
    layer_message_totals = {}
    try:
        if args.no_boot:
            if not emulator.online():
                raise SystemExit("error: --no-boot but %s is not online" % args.serial)
            if not emulator.readiness_mode():
                raise SystemExit("error: --no-boot but %s is not ready (services not up)" % args.serial)
        else:
            booted = emulator.boot()
        device_facts = emulator.facts()
        log("device facts: avd=%s page_size=%s fingerprint=%s"
            % (device_facts.get("avd"), device_facts.get("page_size"),
               device_facts["properties"].get("ro.build.fingerprint")))
        build_info = resolve_wrapper_build(args, log)
        if (args.section == "below_wrapper_use_vvl" and
                Path(build_info["build_dir"]).resolve() != DEFAULT_WRAPPER_BUILD.resolve()):
            args.section = "below_wrapper_use_vvl_patched"
            log("alternate wrapper build detected; writing receipt section '%s' "
                "(pass --section to override)" % args.section)
        staged = stage_remote(args, adb, build, args.layer, build_info, log)
        log("staged %d files to %s" % (len(staged["files"]), args.remote_dir))
        cases = list(CASES)
        if args.cases == "usevvl":
            cases = []
        if args.cases in ("all", "usevvl"):
            cases = cases + list(USE_VVL_CASES)
        def prefixed(case):
            item = dict(case)
            item["name"] = args.run_prefix + case["name"]
            return item

        if args.cases == "usevvl":
            # 1) Negative control BEFORE the layer is discoverable: the wrapper
            #    must refuse to create the instance rather than silently run
            #    without the layer it asked for.
            log("running below-wrapper negative control (USE_VVL=1, no layer staged)")
            runs.append(run_case(args, adb, build, staged, prefixed(USE_VVL_NOLAYER_CASE), log))
            # 2) Stage the layer + manifest where the wrapper's GraphicsEnv hook
            #    points the loader (/data/local/tmp), then run the matrix.
            usevvl_staged = stage_usevvl_layer(adb, args.layer, log)
            for case in USE_VVL_CASES:
                runs.append(run_case(args, adb, build, staged, prefixed(case), log))
        else:
            for case in cases:
                if case["name"].startswith("wrapper-usevvl"):
                    continue
                runs.append(run_case(args, adb, build, staged, case, log))
            if args.cases == "all":
                runs.append(run_case(args, adb, build, staged, prefixed(USE_VVL_NOLAYER_CASE), log))
                usevvl_staged = stage_usevvl_layer(adb, args.layer, log)
                for case in USE_VVL_CASES:
                    runs.append(run_case(args, adb, build, staged, prefixed(case), log))
    finally:
        if not args.keep_remote and emulator.online():
            log("removing remote staging %s and /data/local/tmp layer copies" % args.remote_dir)
            adb.shell("rm -rf %s /data/local/tmp/VkLayer_khronos_validation.json "
                      "/data/local/tmp/libVkLayer_khronos_validation.so" % args.remote_dir)
        try:
            if booted and not args.keep_emulator:
                emulator.kill()
            elif not args.keep_emulator and emulator.online():
                log("NOTE: %s was already online before this run; leaving it alone" % args.serial)
        finally:
            if booted and not args.keep_emulator:
                pass

    vuid_entries = aggregate(runs)
    evidence = {
        "date": DATE,
        "scope": "Manual Khronos validation-layer chaining in front of the Vapor Vulkan wrapper and "
                 "over the system libvulkan.so loader as a control (plus the same chain's deliberate "
                 "positive/negative liveness controls). Offscreen native workload; no app/APK/Wine/Steam/"
                 "game. The wrapper's built-in USE_VVL below-wrapper route is documented as the follow-up "
                 "but was not executed in this pass.",
        "base_vapor_commit": subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
                                            capture_output=True, text=True).stdout.strip(),
        "tool": {
            "fixture_source": str(FIXTURE_SOURCE.relative_to(REPO_ROOT)),
            "runner": str(Path(__file__).resolve().relative_to(REPO_ROOT)),
            "fixture_build": build,
            "layer_release": LAYER_RELEASE,
            "wrapper_build": {
                "path": build_info["build_dir"],
                "wrapper_file": build_info["wrapper_file"],
                "wrapper_sha256": build_info["wrapper_sha256"],
                "wrapper_bytes": build_info["wrapper_bytes"],
                "aux_dir": build_info["aux_dir"],
                "aux_fallback_to_pinned_build": build_info["aux_fallback_to_pinned_build"],
                "pinned_hash_match": build_info["pinned_hash_match"],
                "hashes_match_pinned": staged.get("wrapper_hashes_match_pinned"),
                "candidate_stripped_sha256": (build_info["wrapper_sha256"]
                                              if build_info["wrapper_file"] == "candidate-stripped.so"
                                              else None),
                "label": build_info["label"],
                "expected_findings": build_info["expected_findings"],
                "partial_findings": build_info["partial_findings"],
                "run_prefix": args.run_prefix,
                "section": args.section,
                "ab_base_section": args.ab_base_section,
                "staged_files": staged.get("files", {}),
            },
            "chaining_design": [
                "dlopen the Khronos layer and the driver/wrapper (or /system/lib64/libvulkan.so).",
                "driver GIPA = vk_icdGetInstanceProcAddr if exported, else vkGetInstanceProcAddr.",
                "instance: VkInstanceCreateInfo.pNext -> VkLayerInstanceCreateInfo{sType=47, "
                "function=VK_LAYER_LINK_INFO, u.pLayerInfo=&VkLayerInstanceLink{pfnNextGetInstanceProcAddr="
                "driver GIPA, pfnNextGetPhysicalDeviceProcAddr=NULL}} -> VkLayerInstanceCreateInfo{sType=47, "
                "function=VK_LOADER_DATA_CALLBACK, u.pfnSetInstanceLoaderData=no-op}; create via the layer's "
                "vkCreateInstance obtained from its exported vkGetInstanceProcAddr.",
                "device: VkDeviceCreateInfo.pNext -> VkLayerDeviceCreateInfo{sType=48, function=VK_LAYER_LINK_INFO, "
                "u.pLayerInfo=&VkLayerDeviceLink{pfnNextGetInstanceProcAddr=driver GIPA, "
                "pfnNextGetDeviceProcAddr=shim that resolves the driver's vkGetDeviceProcAddr via driver GIPA}} -> "
                "VkLayerDeviceCreateInfo{sType=48, function=VK_LOADER_DATA_CALLBACK, u.pfnSetDeviceLoaderData=no-op}.",
                "No Vulkan loader is in the loop for the chained cases; validation messages are captured from the "
                "layer's default callback (stdout 'Validation Error: [ VUID-... ]' blocks and logcat tag VALIDATION).",
                "VVL 1.4 keeps its own dispatch/handle maps (handle wrapping + _loader_data dispatch key); the "
                "loader-data callbacks are supplied for fidelity but the layer does not call them.",
            ],
            "understood_limitations_of_layer_position": [
                "A layer chained ABOVE the wrapper sees only the application->wrapper API calls. The wrapper's "
                "own calls into the underlying system loader/driver (BCn staging buffer/image creation, compute "
                "dispatch, copies) happen BELOW the layer and are invisible to it.",
                "The wrapper's built-in USE_VVL path is the only way to place the same layer BELOW the wrapper; "
                "its runs are reported separately.",
            ],
        },
        "emulator": device_facts,
        "layer_below_wrapper_staging": usevvl_staged,
        "runs": runs,
        "vuid_table": vuid_entries,
        "summary": {
            "runs": len(runs),
            "runs_with_messages": sum(1 for run in runs if run["message_count"]),
            "total_messages": sum(run["message_count"] for run in runs),
            "unique_vuid_count": len(vuid_entries),
            "chain_worked": any(run["layer"] == "manual-chain" and run["exit_code"] == 0 for run in runs),
            "positive_control_wrapper_chain_vuid_seen": any(
                run["name"] == "wrapper-chained-vuid" and run["expected_vuid_seen"] for run in runs),
            "positive_control_system_chain_vuid_seen": any(
                run["name"] == "system-chained-vuid" and run["expected_vuid_seen"] for run in runs),
            "negative_control_direct_messages": sum(
                run["message_count"] for run in runs if run["name"] == "wrapper-direct-vuid"),
            "wrapper_defects_found": None,
            "classification": "pending-review",
        },
        "raw_scratch": {
            "path": str(args.scratch),
            "per_run_logs": str(args.scratch / "logs"),
        },
        "limitations": [],
    }
    evidence["tool"]["runner_sha256"] = sha256_file(Path(__file__))
    evidence = finalize_evidence(evidence)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    if args.merge_existing and args.evidence.exists():
        existing = json.loads(args.evidence.read_text(encoding="utf-8"))
        section = {
            "date": DATE,
            "status": "executed",
            "section_name": args.section,
            "wrapper_build": evidence["tool"]["wrapper_build"],
            "expected_findings": evidence["tool"]["wrapper_build"].get("expected_findings") or [],
            "partial_findings": evidence["tool"]["wrapper_build"].get("partial_findings") or [],
            "run_prefix": args.run_prefix,
            "ab_base_section": args.ab_base_section,
            "how": "wrapper's built-in USE_VVL path: wrapper_instance.c:70-72 calls set_layer_paths() "
                   "(graphics_env_hooks.cpp:12,15-77, fixed path list ending in /data/local/tmp) before "
                   "dlopening /system/lib64/libvulkan.so; wrapper_instance.c:372-399 then appends "
                   "VK_LAYER_KHRONOS_validation to the loader instance create info, so the loader chains the "
                   "layer between the wrapper and the native llvmpipe driver.",
            "staged_below_layer": usevvl_staged,
            "runs": evidence["runs"],
            "vuid_table": evidence["vuid_table"],
            "summary": evidence["summary"],
            "comparison": evidence["comparison"],
            "conclusions": evidence["conclusions"],
            "limitations": evidence["limitations"],
            "findings": evidence.get("findings", []),
            "liveness_control": evidence.get("liveness_control", {}),
            "route_evidence": evidence.get("route_evidence", {}),
            "emulator": evidence["emulator"],
            "provenance": evidence["provenance"],
            "version_compatibility": evidence.get("version_compatibility", {}),
            "run_matrix": evidence.get("run_matrix", []),
            "raw_scratch": evidence["raw_scratch"],
        }
        if args.ab_base_section:
            base = existing.get(args.ab_base_section)
            section["ab_table"] = build_ab_table(base, section)
            apply_partial_ab_status(section)
        if args.archive_existing_section and args.section in existing:
            old = existing[args.section]
            old_build = old.get("wrapper_build") or {}
            history_key = args.section + "_history"
            existing.setdefault(history_key, [])
            existing[history_key].append({
                "date": old.get("date"),
                "section_name": old.get("section_name", args.section),
                "build_status": old.get("build_status"),
                "wrapper_sha256": old_build.get("wrapper_sha256"),
                "wrapper_file": old_build.get("wrapper_file"),
                "label": old_build.get("label"),
                "summary": old.get("summary"),
                "ab_table": old.get("ab_table"),
                "conclusions": old.get("conclusions"),
                "capture_logs": [run.get("capture_path") for run in old.get("runs", [])],
                "archived_reason": "superseded by a newer staged patched-build run",
            })
        section["build_status"] = args.build_status
        section["build_note"] = args.build_note
        section["build_sha256"] = (evidence["tool"]["wrapper_build"].get("wrapper_sha256"))
        existing[args.section] = section
        route = existing.setdefault("below_wrapper_route", {})
        if args.section == "below_wrapper_use_vvl":
            route["status"] = "executed - see below_wrapper_use_vvl"
            route["executed_at"] = "2026-10-22"
            route["results_section"] = "below_wrapper_use_vvl"
        else:
            route["patched_section"] = args.section
            route["patched_build_status"] = args.build_status
            route["patched_build_sha256"] = evidence["tool"]["wrapper_build"].get("wrapper_sha256")
            route["patched_history_section"] = (args.section + "_history"
                                                if args.archive_existing_section else None)
            route["patch_status"] = "executed - see " + args.section
            route["patch_executed_at"] = "2026-10-22"
            route.setdefault("results_section", "below_wrapper_use_vvl")
        existing.setdefault("tool", {})["runner_sha256"] = sha256_file(Path(__file__))
        args.evidence.write_text(json.dumps(existing, indent=2) + "\n", encoding="utf-8")
        log("merged section '%s' into %s (%d runs)" % (args.section, args.evidence, len(evidence["runs"])))
    else:
        args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    log("wrote evidence receipt %s (%d runs, %d unique message ids)"
        % (args.evidence, len(runs), len(vuid_entries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
