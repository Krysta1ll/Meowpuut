"""Build the shared Meowpuut lexicon with Windows Chinese script conversion."""
import ctypes
import runpy
import sys
from pathlib import Path


def convert_script(_converter, words, traditional=False):
    if not words:
        return []
    source = "\n".join(words)
    map_string = ctypes.WinDLL("kernel32", use_last_error=True).LCMapStringEx
    map_string.argtypes = [
        ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_wchar_p, ctypes.c_int,
        ctypes.c_wchar_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ssize_t,
    ]
    flags = 0x04000000 if traditional else 0x02000000
    size = map_string("zh-CN", flags, source, -1, None, 0, None, None, 0)
    if not size:
        raise ctypes.WinError(ctypes.get_last_error())
    target = ctypes.create_unicode_buffer(size)
    if not map_string("zh-CN", flags, source, -1, target, size, None, None, 0):
        raise ctypes.WinError(ctypes.get_last_error())
    return target.value.split("\n")


scripts = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(scripts))
compiler = runpy.run_path(str(scripts / "compile-native-lexicon.py"))
main = compiler["main"]
main.__globals__["convert_script"] = convert_script
main(sys.argv[1], None)
