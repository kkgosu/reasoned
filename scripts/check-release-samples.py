from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
mapping = root / "app/build/outputs/mapping/release"
removed = set((mapping / "usage.txt").read_text().splitlines())
retained = (mapping / "mapping.txt").read_text().splitlines()
with zipfile.ZipFile(root / "app/build/outputs/apk/debug/app-debug.apk") as apk:
    debug_dex = b"".join(apk.read(name) for name in apk.namelist() if name.endswith(".dex"))

for suffix in (
    "controllers.MockLesson",
    "model.SampleGraph",
    "fragments.StyleReferenceFragment",
):
    name = "com.kkgosu.reasoned." + suffix
    assert ("L" + name.replace(".", "/") + ";").encode() in debug_dex, f"Missing from debug: {name}"
    assert name in removed, f"Not removed by R8: {name}"
    assert not any(line.startswith((name + " -> ", name + "$")) for line in retained), f"Retained in release: {name}"

print("Sample lesson, graph and style screens exist in debug and are removed from release.")
