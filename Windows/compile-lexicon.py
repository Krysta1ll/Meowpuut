"""Build the shared, pinned Meowpuut lexicon on Windows."""
import runpy
import sys
from pathlib import Path

scripts = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(scripts))
compiler = runpy.run_path(str(scripts / "compile-native-lexicon.py"))
compiler["main"](sys.argv[1])
