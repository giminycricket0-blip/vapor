#!/usr/bin/env python3
"""Run and assert the no-GPU Vulkan loader-path lanes.

This is the thin CI driver around `tools/tests/vulkan-loader-path.c`.  It runs
the loader-mediated harness with an explicit ICD/loader/layer environment,
captures the log, parses the harness' machine-readable KEY=value output and
fails the process if any lane assertion is not met.

Lanes:
  lavapipe  "real" software Vulkan ICD (llvmpipe/lavapipe) selected through
            VK_ICD_FILENAMES/VK_DRIVER_FILES.  Expected outcome: RESULT=SKIP and
            exit code 77 because VK_EXT_map_memory_placed is not implemented by
            lavapipe.  With --validation, any validation ERROR/VUID also fails.
  mock      Vulkan-Tools Mock ICD (with the documented capability patch in
            tools/tests/mock-icd/VkICD_map_memory_placed.patch) or the same mock
            ICD behind the Vulkan-Profiles layer.  Expected outcome:
            MEMORY_MAP_PLACED=1, MEMORY_MAP_RANGE_PLACED=1, RESULT=PASS, placed
            and range-placed vkMapMemory2 succeed, and validation is clean.

Other modes:
  --validate-profile PATH   validate the Vulkan Profiles capability file against
                            the official Khronos profiles schema (network fetch).
  --self-test               exercise the assertion logic against canned harness
                            outputs (no Vulkan driver needed).

Examples (from the repo root):
  python3 tools/tests/vulkan-loader-path.py --lane lavapipe --expect skip \\
      --harness build/vulkan-loader-path \\
      --icd /usr/share/vulkan/icd.d/lvp_icd.x86_64.json --validation \\
      --log-dir logs
  python3 tools/tests/vulkan-loader-path.py --lane mock --expect supported \\
      --harness build/vulkan-loader-path \\
      --icd build/mock-icd/VkICD_mock_icd.json --validation \\
      --layer-path build/vulkan-install/share/vulkan/explicit_layer.d \\
      --loader-lib-dir build/vulkan-install/lib --log-dir logs

Exit codes: 0 all checks pass, 1 check failure, 2 usage/environment error.
No Vulkan SDK, GPU or display server is required by this script itself.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

DEFAULT_PROFILE = Path(__file__).resolve().parent / "mock-icd" / "VP_VAPOR_map_memory_placed.json"
DEFAULT_SCHEMA_URL = "https://schema.khronos.org/vulkan/profiles-0.8-latest.json"


def parse_output(text):
    """Parse the harness protocol (KEY=value lines; repeated keys are lists)."""
    fields = {}
    for line in text.replace("\r\n", "\n").split("\n"):
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if not key or key != key.strip() or " " in key:
            continue
        fields.setdefault(key, []).append(value)
    return fields


def get(fields, key, default=None):
    values = fields.get(key)
    return values[-1] if values else default


def _int(fields, key, default=None):
    value = get(fields, key)
    if value is None:
        return default
    try:
        return int(value.split()[0])
    except (TypeError, ValueError):
        return default


class Checks:
    def __init__(self):
        self.checks = []

    def expect(self, name, condition, detail=""):
        self.checks.append({"name": name, "passed": bool(condition), "detail": detail})
        return bool(condition)

    def passed(self):
        return all(check["passed"] for check in self.checks)


def evaluate(fields, exit_code, expect_skip, validation, require_extension=False):
    """Evaluate the harness output against one lane's expectations."""
    checks = Checks()
    expected_exit = 77 if expect_skip else 0
    checks.expect("exit-code", exit_code == expected_exit,
                  "exit=%s expected=%s" % (exit_code, expected_exit))
    result = get(fields, "RESULT")
    expected_result = "SKIP" if expect_skip else "PASS"
    checks.expect("result", result == expected_result,
                  "RESULT=%s expected=%s" % (result, expected_result))
    if validation:
        checks.expect("validation-available", _int(fields, "VALIDATION_AVAILABLE", 0) == 1,
                      "VALIDATION_AVAILABLE=%s" % get(fields, "VALIDATION_AVAILABLE"))
        checks.expect("validation-errors", _int(fields, "VALIDATION_ERRORS", 1) == 0,
                      "VALIDATION_ERRORS=%s" % get(fields, "VALIDATION_ERRORS"))
        checks.expect("vuid-errors", _int(fields, "VUID_ERRORS", 1) == 0,
                      "VUID_ERRORS=%s" % get(fields, "VUID_ERRORS"))
        checks.expect("no-validation-error-lines",
                      not any(key == "VALIDATION_ERROR" for key in fields),
                      "%d VALIDATION_ERROR line(s)" % len(fields.get("VALIDATION_ERROR", [])))
    if expect_skip:
        if not require_extension:
            checks.expect("skip-reason", bool(get(fields, "REASON")),
                          "REASON=%s" % get(fields, "REASON"))
    else:
        checks.expect("extension-present", _int(fields, "EXTENSION_PRESENT", 0) == 1,
                      "EXTENSION_PRESENT=%s" % get(fields, "EXTENSION_PRESENT"))
        checks.expect("memory-map-placed", _int(fields, "MEMORY_MAP_PLACED", 0) == 1,
                      "MEMORY_MAP_PLACED=%s" % get(fields, "MEMORY_MAP_PLACED"))
        checks.expect("memory-map-range-placed",
                      _int(fields, "MEMORY_MAP_RANGE_PLACED", 0) == 1,
                      "MEMORY_MAP_RANGE_PLACED=%s" % get(fields, "MEMORY_MAP_RANGE_PLACED"))
        checks.expect("device-created", _int(fields, "DEVICE_CREATED", 0) == 1,
                      "DEVICE_CREATED=%s" % get(fields, "DEVICE_CREATED"))
        checks.expect("placed-map-success", _int(fields, "PLACED_MAP_RESULT", -1) == 0,
                      "PLACED_MAP_RESULT=%s" % get(fields, "PLACED_MAP_RESULT"))
        checks.expect("range-placed-map-success",
                      _int(fields, "PLACED_RANGE_MAP_RESULT", -1) == 0,
                      "PLACED_RANGE_MAP_RESULT=%s" % get(fields, "PLACED_RANGE_MAP_RESULT"))
        if "UNMAP2_RESULT" in fields:
            checks.expect("unmap2-success", _int(fields, "UNMAP2_RESULT", -1) == 0,
                          "UNMAP2_RESULT=%s" % get(fields, "UNMAP2_RESULT"))
    return checks


def build_env(args):
    env = dict(os.environ)
    if args.icd:
        icd = str(Path(args.icd).resolve())
        env["VK_ICD_FILENAMES"] = icd
        env["VK_DRIVER_FILES"] = icd
    if args.layer_path:
        paths = [str(Path(p).resolve()) for p in args.layer_path]
        if env.get("VK_LAYER_PATH"):
            paths.append(env["VK_LAYER_PATH"])
        env["VK_LAYER_PATH"] = os.pathsep.join(paths)
    if args.instance_layers:
        env["VK_INSTANCE_LAYERS"] = args.instance_layers
    if args.loader_lib_dir:
        lib_dir = str(Path(args.loader_lib_dir).resolve())
        for name in ("LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH", "DYLD_FALLBACK_LIBRARY_PATH"):
            current = env.get(name)
            env[name] = lib_dir + (os.pathsep + current if current else "")
    if args.loader_debug:
        env["VK_LOADER_DEBUG"] = args.loader_debug
    return env


def run_lane(args):
    harness = Path(args.harness).resolve()
    if not harness.is_file():
        print("LANE_RESULT=FAIL\nREASON=harness-not-found:%s" % harness)
        return 2
    log_dir = Path(args.log_dir).resolve() if args.log_dir else Path.cwd()
    log_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log_path = log_dir / ("%s-%s.log" % (args.lane, stamp))
    command = [str(harness), "--expect", "skip" if args.expect_skip else "supported"]
    if args.validation:
        command.append("--validation")
    if args.print_all:
        command.append("--print-all")
    if args.require_extension:
        command.append("--require-extension")
    env = build_env(args)
    exported = {key: value for key, value in env.items() if key.startswith("VK_") or
                key in ("LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH",
                        "DYLD_FALLBACK_LIBRARY_PATH")}
    print("LANE=%s" % args.lane)
    print("HARNESS=%s" % harness)
    print("COMMAND=%s" % " ".join(command))
    for key in sorted(exported):
        print("ENV_%s=%s" % (key, exported[key]))
    try:
        proc = subprocess.run(command, env=env, capture_output=True, text=True,
                              timeout=args.timeout)
        stdout, stderr, exit_code = proc.stdout, proc.stderr, proc.returncode
    except subprocess.TimeoutExpired as error:
        stdout = (error.stdout or b"").decode(errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = "timeout after %ss" % args.timeout
        exit_code = -1
    log_text = ["# lane: %s" % args.lane,
                "# command: %s" % " ".join(command),
                "# exit_code: %s" % exit_code,
                "".join(sorted("# %s=%s\n" % (k, v) for k, v in exported.items())),
                "# --- stdout ---", stdout,
                "# --- stderr ---", stderr]
    log_path.write_text("\n".join(log_text), encoding="utf-8")
    fields = parse_output(stdout)
    checks = evaluate(fields, exit_code, args.expect_skip, args.validation,
                      args.require_extension)
    for check in checks.checks:
        print("CHECK %s %s (%s)" % (check["name"],
                                    "PASS" if check["passed"] else "FAIL",
                                    check["detail"]))
    print("LOG=%s" % log_path)
    if checks.passed():
        print("LANE_RESULT=PASS")
        return 0
    failed = [check["name"] for check in checks.checks if not check["passed"]]
    print("LANE_RESULT=FAIL")
    print("FAILED_CHECKS=%s" % ",".join(failed))
    return 1


def validate_profile(profile_path, schema_url):
    profile_path = Path(profile_path).resolve()
    try:
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print("PROFILE_VALID=0\nREASON=%s" % error)
        return 2
    try:
        with urllib.request.urlopen(schema_url, timeout=60) as response:
            schema = json.load(response)
    except Exception as error:  # network problems must not look like a profile error
        print("PROFILE_VALID=0\nREASON=schema-fetch-failed:%s" % error)
        return 2
    try:
        import jsonschema
    except ImportError:
        print("PROFILE_VALID=0\nREASON=jsonschema-module-missing")
        return 2
    try:
        jsonschema.validate(profile, schema)
    except jsonschema.ValidationError as error:
        print("PROFILE=%s\nSCHEMA=%s\nPROFILE_VALID=0\nREASON=%s at %s"
              % (profile_path, schema_url, error.message, list(error.absolute_path)))
        return 1
    print("PROFILE=%s\nSCHEMA=%s\nPROFILE_VALID=1" % (profile_path, schema_url))
    return 0


def self_test():
    cases = []

    def case(name, output, exit_code, expect_skip, validation, should_pass,
             require_extension=False):
        fields = parse_output(output)
        checks = evaluate(fields, exit_code, expect_skip, validation,
                          require_extension)
        cases.append({"name": name, "passed": checks.passed() == should_pass,
                      "checks": checks.checks})

    supported_ok = "\n".join([
        "HARNESS=vulkan-loader-path",
        "EXPECT=supported",
        "VALIDATION_REQUESTED=1",
        "INSTANCE_API=1.4.363",
        "VALIDATION_AVAILABLE=1",
        "VALIDATION_ERRORS=0",
        "VUID_ERRORS=0",
        "PHYSICAL_DEVICES=1",
        "DEVICE_NAME=Vulkan Mock Device",
        "EXTENSION_PRESENT=1",
        "MEMORY_MAP_PLACED=1",
        "MEMORY_MAP_RANGE_PLACED=1",
        "DEVICE_CREATED=1",
        "PLACED_MAP_RESULT=0 (VK_SUCCESS)",
        "UNMAP2_RESULT=0 (VK_SUCCESS)",
        "PLACED_RANGE_MAP_RESULT=0 (VK_SUCCESS)",
        "RESULT=PASS",
    ])
    skip_ok = "\n".join([
        "HARNESS=vulkan-loader-path",
        "EXPECT=skip",
        "VALIDATION_REQUESTED=1",
        "VALIDATION_AVAILABLE=1",
        "VALIDATION_ERRORS=0",
        "VUID_ERRORS=0",
        "PHYSICAL_DEVICES=1",
        "DEVICE_NAME=llvmpipe (LLVM 21.0.0, 128 bits)",
        "EXTENSION_PRESENT=0",
        "MEMORY_MAP_PLACED=0",
        "MEMORY_MAP_RANGE_PLACED=0",
        "REASON=extension or placed-map feature bits absent",
        "RESULT=SKIP",
    ])
    case("supported-lane-passes", supported_ok, 0, False, True, True)
    case("skip-lane-passes", skip_ok, 77, True, True, True)
    case("unexpected-hardware-fails", supported_ok, 0, True, True, False)
    case("missing-feature-fails",
         supported_ok.replace("MEMORY_MAP_PLACED=1", "MEMORY_MAP_PLACED=0"),
         0, False, True, False)
    case("vuid-fails",
         supported_ok.replace("VUID_ERRORS=0", "VUID_ERRORS=1"),
         0, False, True, False)
    case("validation-missing-fails",
         supported_ok.replace("VALIDATION_AVAILABLE=1", "VALIDATION_AVAILABLE=0"),
         0, False, True, False)
    case("wrong-exit-fails", supported_ok, 1, False, True, False)

    passed = all(case["passed"] for case in cases)
    for case in cases:
        print("SELFTEST %s %s" % (case["name"], "PASS" if case["passed"] else "FAIL"))
    print("SELFTEST_RESULT=%s" % ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Run and assert no-GPU Vulkan loader-path lanes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument("--lane", default=None, help="lane label (lavapipe, mock, ...)")
    parser.add_argument("--harness", default=None, help="compiled harness binary")
    parser.add_argument("--expect", default="supported", choices=["skip", "supported"],
                        help="expected capability outcome (default: supported)")
    parser.add_argument("--validation", action="store_true",
                        help="require the Khronos validation layer and clean output")
    parser.add_argument("--icd", default=None, help="ICD JSON for VK_ICD_FILENAMES/VK_DRIVER_FILES")
    parser.add_argument("--layer-path", nargs="+", default=None,
                        help="directories for VK_LAYER_PATH")
    parser.add_argument("--instance-layers", default=None,
                        help="optional VK_INSTANCE_LAYERS value (the harness enables "
                             "validation itself)")
    parser.add_argument("--loader-lib-dir", default=None,
                        help="directory prepended to LD_LIBRARY_PATH/DYLD_LIBRARY_PATH")
    parser.add_argument("--loader-debug", default=None, help="VK_LOADER_DEBUG value")
    parser.add_argument("--log-dir", default=None, help="directory for per-lane logs")
    parser.add_argument("--timeout", type=int, default=120, help="harness timeout (s)")
    parser.add_argument("--print-all", action="store_true",
                        help="pass --print-all to the harness")
    parser.add_argument("--require-extension", action="store_true",
                        help="pass --require-extension to the harness")
    parser.add_argument("--validate-profile", default=None, metavar="PATH",
                        help="validate a Vulkan Profiles JSON file and exit")
    parser.add_argument("--schema-url", default=DEFAULT_SCHEMA_URL,
                        help="profiles schema URL (default: %(default)s)")
    parser.add_argument("--self-test", action="store_true",
                        help="test the assertion logic with canned outputs")
    args = parser.parse_args(argv)
    args.expect_skip = args.expect == "skip"
    return args


def main(argv=None):
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    if args.validate_profile:
        return validate_profile(args.validate_profile, args.schema_url)
    if not args.lane or not args.harness:
        print("REASON=--lane and --harness are required (or use --self-test / "
              "--validate-profile)")
        return 2
    return run_lane(args)


if __name__ == "__main__":
    sys.exit(main())
