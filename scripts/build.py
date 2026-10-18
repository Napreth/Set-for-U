#!/usr/bin/env python3
"""Build Android APK and self-contained desktop portable archives (Python stdlib only)."""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
DIST = BUILD / "dist"
MAIN = "com.napreth.set4u.desktop.MainKt"


def run(args, **kwargs):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, **kwargs)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_remove(path):
    resolved = path.resolve()
    if BUILD.resolve() not in resolved.parents:
        raise ValueError(f"Refusing to remove outside project build directory: {resolved}")
    if path.exists():
        def remove_readonly(function, target, exception):
            # jpackage marks its launcher read-only on Windows. A running app still raises an error.
            os.chmod(target, stat.S_IWRITE | stat.S_IREAD)
            function(target)
        shutil.rmtree(path, onerror=remove_readonly)


def download(item, offline):
    path = BUILD / "downloads" / item["file"]
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        if offline:
            raise RuntimeError(f"Missing offline dependency: {path.name}")
        print(f"Downloading {path.name}", flush=True)
        temporary = path.with_suffix(path.suffix + ".partial")
        request = urllib.request.Request(item["url"], headers={"User-Agent": "Set-for-U-build/0.1"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(path)
    if sha256(path) != item["sha256"]:
        raise RuntimeError(f"Checksum mismatch: {path}; remove this cached file before retrying")
    return path


def zip_extract(archive, destination, select=lambda name: name):
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            relative = select(member.filename)
            if not relative or member.is_dir():
                continue
            path = destination / relative
            if destination.resolve() not in path.resolve().parents:
                raise ValueError(f"Unsafe archive entry: {member.filename}")
            path.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(member) as source, path.open("wb") as output:
                shutil.copyfileobj(source, output)


def add_bytes(bundle, name, data, executable=False):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o755 if executable else 0o644
    bundle.addfile(info, io.BytesIO(data))


def adb_member(name):
    prefix = "platform-tools/"
    if not name.startswith(prefix):
        return None
    relative = name[len(prefix):]
    if relative in ("adb", "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt", "source.properties") or relative.startswith("lib64/"):
        return relative
    return None


def metadata(target, version, base_version, build_type, lock):
    return json.dumps({"application": "Set-for-U", "version": version, "baseVersion": base_version,
                       "buildType": build_type, "target": target,
                       "runtime": lock["runtime"], "adb": lock["adb"]}, indent=2).encode()


def verify_device_bundle(container, host_jar=False):
    with zipfile.ZipFile(container) as archive:
        index = json.loads(archive.read("device/device-tasks.json"))
        payload = archive.read("device/set4u-device.jar")
        if hashlib.sha256(payload).hexdigest() != index["sha256"]:
            raise RuntimeError(f"Device bundle checksum mismatch in {container}")
        if host_jar:
            names = set(archive.namelist())
            for entry in index["entries"]:
                if entry["entry"].replace(".", "/") + ".class" in names:
                    raise RuntimeError(f"Device implementation leaked into host JAR: {entry['entry']}")
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if "classes.dex" not in archive.namelist() or not archive.read("classes.dex").startswith(b"dex\n"):
            raise RuntimeError(f"Missing DEX in device bundle: {container}")


def windows(version, base_version, build_type, lock, inputs, dist, offline):
    if os.name != "nt":
        raise RuntimeError("Windows .exe launcher is built on Windows; use the Windows CI job")
    stage = BUILD / "portable" / build_type / "windows-x64"
    safe_remove(stage)
    stage.mkdir(parents=True)
    runtime = stage / "jre"
    zip_extract(download(lock["runtime"], offline), runtime, lambda name: name.partition("/")[2])
    java_home = Path(os.environ["JAVA_HOME"])
    app = stage / "Set-for-U"
    run([java_home / "bin/jpackage.exe", "--type", "app-image", "--dest", stage,
         "--input", inputs, "--main-jar", f"desktop-{version}.jar", "--main-class", MAIN,
         # Windows product versions are numeric; the JAR and build-info carry the debug suffix.
         "--name", "Set-for-U", "--app-version", base_version, "--vendor", "napreth",
         "--icon", ROOT / "assets/icon/set4u.ico", "--runtime-image", runtime,
         "--java-options", "-Dfile.encoding=UTF-8", "--java-options", "-Dset4u.home=$APPDIR/.."])
    zip_extract(download(lock["adb"], offline), app / "tools", adb_member)
    shutil.copy2(ROOT / "LICENSE", app / "LICENSE")
    shutil.copytree(ROOT / "assets/licenses", app / "licenses")
    (app / "build-info.json").write_bytes(metadata("windows-x64", version, base_version, build_type, lock))
    (app / "Set-for-U-cli.cmd").write_text(
        '@echo off\nset "SET4U_HOME=%~dp0"\n"%~dp0runtime\\bin\\java.exe" -Dfile.encoding=UTF-8 -cp "%~dp0app\\*" '
        + MAIN + ' %*\nexit /b %errorlevel%\n', encoding="utf-8")
    (app / "README.txt").write_text("Extract the whole archive, then launch Set-for-U.exe.\n"
                                     "CLI: Set-for-U-cli.cmd --help\nJava 21 and adb are included.\n", encoding="utf-8")
    run([app / "runtime/bin/java.exe", "-cp", str(app / "app/*"), MAIN, "--self-test"])
    run([app / "tools/adb.exe", "version"])
    output = dist / f"Set-for-U-{version}-windows-x64.zip"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for path in sorted(app.rglob("*")):
            if path.is_file():
                bundle.write(path, "Set-for-U/" + path.relative_to(app).as_posix())
    return output


def linux(version, base_version, build_type, lock, inputs, dist, offline):
    runtime = download(lock["runtime"], offline)
    adb = download(lock["adb"], offline)
    output = dist / f"Set-for-U-{version}-linux-x64.tar.gz"
    prefix = "Set-for-U/"
    with tarfile.open(output, "w:gz", compresslevel=6) as bundle:
        with tarfile.open(runtime, "r:gz") as source:
            for original in source:
                parts = PurePosixPath(original.name).parts
                if len(parts) < 2:
                    continue
                if original.name.startswith("/") or ".." in parts:
                    raise ValueError("Unsafe runtime archive")
                member = copy.copy(original)
                member.pax_headers = {key: value for key, value in original.pax_headers.items() if key not in ("path", "linkpath")}
                member.name = prefix + "runtime/" + "/".join(parts[1:])
                member.uid = member.gid = 0
                member.uname = member.gname = ""
                if member.islnk():
                    member.linkname = prefix + "runtime/" + "/".join(PurePosixPath(member.linkname).parts[1:])
                bundle.addfile(member, source.extractfile(original) if member.isfile() else None)
        with zipfile.ZipFile(adb) as source:
            for original in source.infolist():
                name = adb_member(original.filename)
                if name and not original.is_dir():
                    add_bytes(bundle, prefix + "tools/" + name, source.read(original), executable=name == "adb")
        for path in sorted(inputs.glob("*.jar")):
            add_bytes(bundle, prefix + "lib/" + path.name, path.read_bytes())
        add_bytes(bundle, prefix + "set4u.png", (ROOT / "assets/icon/set4u-512.png").read_bytes())
        add_bytes(bundle, prefix + "LICENSE", (ROOT / "LICENSE").read_bytes())
        for path in sorted((ROOT / "assets/licenses").glob("*.txt")):
            add_bytes(bundle, prefix + "licenses/" + path.name, path.read_bytes())
        launcher = '#!/bin/sh\nset -eu\nBASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexport SET4U_HOME="$BASE"\n'
        launcher += 'exec "$BASE/runtime/bin/java" -Dfile.encoding=UTF-8 -cp "$BASE/lib/*" ' + MAIN + ' "$@"\n'
        add_bytes(bundle, prefix + "Set-for-U", launcher.encode(), executable=True)
        add_bytes(bundle, prefix + "build-info.json", metadata("linux-x64", version, base_version, build_type, lock))
        add_bytes(bundle, prefix + "README.txt", b"Extract the whole archive, then run ./Set-for-U.\nCLI: ./Set-for-U --help\nJava 21 and adb are included.\nRequires a glibc Linux desktop with X11 or XWayland libraries for the GUI.\n")
    with tarfile.open(output, "r:gz") as bundle:
        names = set(bundle.getnames())
        for required in ("Set-for-U", "runtime/bin/java", f"lib/desktop-{version}.jar", "tools/adb"):
            assert prefix + required in names, required
        assert bundle.getmember(prefix + "Set-for-U").mode & 0o111
        assert bundle.getmember(prefix + "runtime/bin/java").mode & 0o111
        assert bundle.getmember(prefix + "tools/adb").mode & 0o111
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--targets", nargs="+", choices=("windows-x64", "linux-x64", "android"),
                        default=["windows-x64", "linux-x64", "android"] if os.name == "nt" else ["linux-x64", "android"])
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug",
                        help="debug: test-signed APK, -debug version suffix (default); release: unsigned APK")
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--skip-build", action="store_true", help="Package existing Gradle outputs")
    parser.add_argument("--offline", action="store_true", help="Use only checksum-verified dependency cache")
    options = parser.parse_args()
    properties = dict(line.strip().split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    base_version = properties["set4u.version"]
    build_type = options.build_type
    version = base_version + ("-debug" if build_type == "debug" else "")
    dist = DIST / build_type
    dist.mkdir(parents=True, exist_ok=True)
    if not options.skip_build:
        tasks = [] if options.skip_tests else [":core:test", ":desktop:test"]
        if any(target != "android" for target in options.targets):
            tasks.append(":desktop:stagePortable")
        if "android" in options.targets:
            tasks.append(":android:assemble" + build_type.capitalize())
        gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        java_home = os.environ.get("JAVA_HOME")
        args = ([gradle] if os.name == "nt" else ["sh", gradle]) + ["--no-daemon", "--console=plain", f"-Pset4u.buildType={build_type}"]
        if options.offline:
            args.append("--offline")
        if java_home:
            args += ["-Dorg.gradle.java.installations.auto-detect=false", f"-Dorg.gradle.java.installations.paths={java_home}"]
        run(args + tasks)
    lock = json.loads((ROOT / "scripts/runtime-lock.json").read_text(encoding="utf-8-sig"))
    inputs = ROOT / "set4u/desktop/build/portable-input" / build_type
    if any(target != "android" for target in options.targets):
        verify_device_bundle(inputs / f"core-{version}.jar", host_jar=True)
    artifacts = []
    for target in dict.fromkeys(options.targets):
        if target == "android":
            apk_name = "android-debug.apk" if build_type == "debug" else "android-release-unsigned.apk"
            source = ROOT / "set4u/android/build/outputs/apk" / build_type / apk_name
            verify_device_bundle(source)
            # AGP records the packaged version alongside its APK. Reject stale --skip-build outputs.
            apk_metadata = json.loads((source.parent / "output-metadata.json").read_text(encoding="utf-8"))
            if apk_metadata["elements"][0]["versionName"] != version:
                raise RuntimeError(f"APK version does not match {version}; rebuild :android:assemble{build_type.capitalize()}")
            unsigned = "-unsigned" if build_type == "release" else ""
            destination = dist / f"Set-for-U-{version}-android{unsigned}.apk"
            shutil.copy2(source, destination)
            artifacts.append(destination)
        else:
            if not (inputs / f"desktop-{version}.jar").is_file():
                raise RuntimeError("Missing staged desktop JAR; run :desktop:stagePortable")
            artifacts.append((windows if target == "windows-x64" else linux)(
                version, base_version, build_type, lock[target], inputs, dist, options.offline))
    manifest = {"version": version, "baseVersion": base_version, "buildType": build_type,
                "artifacts": [{"file": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)} for path in artifacts]}
    (dist / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (dist / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['file']}\n" for item in manifest["artifacts"]), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
