#!/usr/bin/env python3
"""Run Japanese key handling on the host using an empty Android dictionary."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[2]
stubs = {
    "android/content/res/AssetManager.java": """package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException { throw new java.io.IOException(); }
}""",
    "org/json/JSONObject.java": """package org.json;
public class JSONObject {
    public JSONObject() {} public JSONObject(String s) {}
    public JSONArray optJSONArray(String key) { return null; }
    public String optString(String key) { return \"\"; }
}""",
    "org/json/JSONArray.java": """package org.json;
public class JSONArray {
    public int length() { return 0; }
    public JSONObject optJSONObject(int index) { return null; }
}""",
}
with tempfile.TemporaryDirectory(prefix="meowpuut-japanese-") as directory:
    temp = pathlib.Path(directory)
    paths = []
    for name, content in stubs.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        paths.append(str(path))
    subprocess.run(["javac", "-d", str(temp), *paths,
                    str(root / "Android/src/main/java/app/meowpuut/android/Japanese.java"),
                    str(root / "Android/tests/JapaneseOverflowTest.java")], check=True)
    subprocess.run(["java", "-cp", str(temp), "app.meowpuut.android.JapaneseOverflowTest"], check=True)
