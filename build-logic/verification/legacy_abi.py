"""Require the current public JVM descriptors to still contain everything a published release had.

`api/<name>.txt` is a snapshot of the current candidate, so on its own it cannot notice that a shape
older consumers were compiled against has disappeared. For Kotlin that includes the synthetic
default-argument constructors and `$default` methods: adding a parameter to a constructor silently
removes the one that call sites without that argument were compiled to, and the old binary then
fails with `NoSuchMethodError`. `api/legacy/<version>/<name>.txt` holds the same dump taken from the
published Maven Central artifacts; `check` requires the current dump to be a superset of it.

    legacy_abi.py check    --root <repo>
    legacy_abi.py generate --root <repo> --version 1.0.0 --artifacts-dir <maven layout root>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

GROUP_PATH = "io/github/jichuo1/meguviz"
HEADER = re.compile(r"^(?P<modifiers>(?:[\w-]+ )*?)(?P<kind>@interface|interface|class|enum) (?P<name>[^\s<]+)")


def dump_name(module: str) -> str:
    """core and widgets share one baseline file, every other module has its own."""
    return "public-api" if module in {"core", "widgets"} else f"ui-{module}"


def split_top_level(text: str, separator: str) -> list[str]:
    parts, depth, current = [], 0, []
    for character in text:
        if character == "<":
            depth += 1
        elif character == ">":
            depth -= 1
        if character == separator and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    parts.append("".join(current).strip())
    return [part for part in parts if part]


def parse_header(line: str) -> dict:
    text = line.strip()
    if text.endswith("{"):
        text = text[:-1].strip()
    declaration, supertypes = text, []
    depth = 0
    for index in range(len(text)):
        if text[index] == "<":
            depth += 1
        elif text[index] == ">":
            depth -= 1
        elif depth == 0 and (text.startswith(" extends ", index) or text.startswith(" implements ", index)):
            declaration = text[:index]
            rest = text[index:]
            supertypes = [item for keyword in (" extends ", " implements ")
                          for segment in re.findall(rf"{keyword}(.*?)(?= (?:extends|implements) |$)", rest)
                          for item in split_top_level(segment, ",")]
            break
    match = HEADER.match(declaration)
    if not match:
        raise ValueError(f"Unrecognised class header: {line!r}")
    modifiers = set(match.group("modifiers").split())
    return {"kind": match.group("kind"), "name": match.group("name"),
            "shape": frozenset(modifiers & {"final", "abstract"}), "supertypes": frozenset(supertypes)}


def parse_dump(text: str) -> dict[str, dict]:
    """Class name -> {header, members}; a member is its declaration plus its descriptor lines."""
    # javap prints blank lines between members, so sections are found by indentation, not by gaps:
    # a class header starts in column 0, a member at two spaces, its descriptor at four.
    classes: dict[str, dict] = {}
    header, members, current = None, set(), []

    def close_member() -> None:
        nonlocal current
        if current:
            members.add("\n".join(current))
        current = []

    def close_class() -> None:
        close_member()
        if header is not None:
            classes[header["name"]] = {"header": header, "members": set(members)}
        members.clear()

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.startswith("//") or line == "}":
            continue
        if not line.startswith(" "):
            close_class()
            header = parse_header(line)
        elif re.match(r"^ {2}\S", line):
            close_member()
            current = [line.strip()]
        elif current:
            current.append(line.strip())
    close_class()
    return classes


def read_allowed(path: Path) -> set[str]:
    """Reviewed, intentional removals: `<class> :: <member declaration>` per line, `#` comments."""
    if not path.is_file():
        return set()
    allowed = set()
    for line in path.read_text("utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            allowed.add(re.sub(r"\s+", " ", line))
    return allowed


def compare(legacy: dict[str, dict], current: dict[str, dict], allowed: set[str]) -> list[str]:
    problems = []
    for name, old in sorted(legacy.items()):
        if f"{name} :: *" in allowed:
            continue
        new = current.get(name)
        if new is None:
            problems.append(f"class removed: {name}")
            continue
        before, after = old["header"], new["header"]
        if before["kind"] != after["kind"]:
            problems.append(f"{name}: kind changed {before['kind']} -> {after['kind']}")
        if before["shape"] != after["shape"]:
            problems.append(f"{name}: modifiers changed {sorted(before['shape'])} -> {sorted(after['shape'])}")
        for supertype in sorted(before["supertypes"] - after["supertypes"]):
            problems.append(f"{name}: no longer extends or implements {supertype}")
        for member in sorted(old["members"] - new["members"]):
            declaration = re.sub(r"\s+", " ", member.split("\n", 1)[0])
            if f"{name} :: {declaration}" in allowed:
                continue
            descriptor = member.split("\n", 1)[1].strip() if "\n" in member else ""
            problems.append(f"{name}: member missing or changed: {declaration} {descriptor}".rstrip())
    return problems


def check(root: Path) -> int:
    started = time.time()
    output = root / "build/reports/legacy-abi"
    output.mkdir(parents=True, exist_ok=True)
    checks, errors = [], []
    try:
        versions = sorted(path for path in (root / "api/legacy").glob("*") if path.is_dir())
        if not versions:
            raise RuntimeError("api/legacy has no published-release baseline; run `legacy_abi.py generate`")
        for version in versions:
            allowed = read_allowed(version / "allowed-removals.txt")
            files = sorted(version.glob("*.txt"))
            baselines = [path for path in files if path.name != "allowed-removals.txt"]
            if not baselines:
                raise RuntimeError(f"{version.name} holds no baseline dumps")
            for baseline in baselines:
                current_file = root / "api" / baseline.name
                if not current_file.is_file():
                    problems = [f"current dump api/{baseline.name} is missing"]
                else:
                    problems = compare(parse_dump(baseline.read_text("utf-8")), parse_dump(current_file.read_text("utf-8")), allowed)
                errors.extend(f"{version.name}/{baseline.stem}: {problem}" for problem in problems)
                checks.append({"release": version.name, "dump": baseline.stem, "status": "failed" if problems else "passed",
                               "problems": problems[:50], "baseline_sha256": hashlib.sha256(baseline.read_bytes()).hexdigest()})
    except (OSError, RuntimeError, ValueError) as failure:
        errors.append(str(failure))
    receipt = {"status": "failed" if errors else "passed", "started_epoch": started, "finished_epoch": time.time(),
               "scope": "Source-level JVM descriptors of published Central artifacts; not a behavior or device claim",
               "checks": checks, "errors": errors[:200]}
    (output / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": receipt["status"], "checks": [{"dump": c["dump"], "release": c["release"], "status": c["status"]} for c in checks],
                      "errors": errors[:20], "receipt": str(output / "receipt.json")}, ensure_ascii=False))
    return 0 if not errors else 1


def generate(root: Path, version: str, artifacts: Path) -> int:
    javap = shutil.which("javap")
    if not javap:
        raise RuntimeError("javap is required")
    base = artifacts / GROUP_PATH
    found: dict[str, list[tuple[str, Path]]] = {}
    for directory in sorted(base.glob(f"meguviz-ui-*/{version}")):
        artifact = directory.parent.name
        module = artifact[len("meguviz-ui-"):]
        for extension in ("aar", "jar"):
            candidate = directory / f"{artifact}-{version}.{extension}"
            if candidate.is_file():
                found.setdefault(dump_name(module), []).append((module, candidate))
                break
    if not found:
        raise RuntimeError(f"No meguviz-ui-* {version} artifacts under {base}")
    work = root / "build/reports/legacy-abi/generate"
    work.mkdir(parents=True, exist_ok=True)
    jars: dict[str, Path] = {}
    for entries in found.values():
        for module, artifact in entries:
            if artifact.suffix == ".aar":
                with zipfile.ZipFile(artifact) as archive:
                    jar = work / f"{module}.jar"
                    jar.write_bytes(archive.read("classes.jar"))
            else:
                jar = artifact
            jars[module] = jar
    classpath = os.pathsep.join(str(path) for path in jars.values())
    target = root / "api/legacy" / version
    target.mkdir(parents=True, exist_ok=True)
    for name, entries in sorted(found.items()):
        sections: list[str] = []
        for module, artifact in entries:
            with zipfile.ZipFile(jars[module]) as archive:
                classes = sorted(item[:-6].replace("/", ".") for item in archive.namelist()
                                 if item.endswith(".class") and item.startswith("io/github/jichuo1/meguviz/ui/"))
            result = subprocess.run([javap, "-public", "-s", "-classpath", classpath, *classes],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            if result.returncode:
                raise RuntimeError(f"javap failed for {module}: {result.stderr}")
            sections.extend(section.strip() for section in re.split(r'Compiled from "[^"]+"\r?\n', result.stdout)
                            if section.lstrip().startswith("public "))
        provenance = "".join(f"// source {artifact.name} sha256={hashlib.sha256(artifact.read_bytes()).hexdigest()}\n" for _, artifact in entries)
        dump = (f"// JVM public descriptors of the published release {version} (Maven Central, io.github.jichuo1.meguviz).\n"
                f"// Generated by build-logic/verification/legacy_abi.py generate; do not edit by hand.\n{provenance}\n"
                + "\n\n".join(sorted(sections)) + "\n")
        (target / f"{name}.txt").write_text(dump, encoding="utf-8")
        print(f"wrote api/legacy/{version}/{name}.txt ({len(sections)} classes)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("check", "generate"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--version")
    parser.add_argument("--artifacts-dir", type=Path)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    if args.mode == "check":
        return check(root)
    if not args.version or not args.artifacts_dir:
        parser.error("generate needs --version and --artifacts-dir")
    return generate(root, args.version, args.artifacts_dir.resolve(strict=True))


if __name__ == "__main__":
    sys.exit(main())
