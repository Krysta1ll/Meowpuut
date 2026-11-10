"""Binary serializer for MIMO-BIN-1 and MIMO-BASE-1 (mimo_base.bin) SoA lexicons."""
import array
import pathlib
import re
import struct
import sys

MIMO_BIN_MAGIC = b"MIMO-BIN-1\x00\x00"
MIMO_BASE_MAGIC = b"MIMO-BASE-1\x00"

FLAG_FULLY_SPELLED = 1 << 0
FLAG_SINGLE_HAN_HANT = 1 << 1
FLAG_SINGLE_HAN_HANS = 1 << 2
FLAG_VERIFIED_SPELLING = 1 << 3

_VOWELS = set(b"aeiouv")
_SYLLABLE_RE = re.compile(r"[^1-5]+[1-5]")


def _is_han_scalar(code: int) -> bool:
    return (
        0x3400 <= code <= 0x9FFF
        or 0xF900 <= code <= 0xFAFF
        or 0x20000 <= code <= 0x323AF
    )


def _is_single_han(text: str, utf8_len: int) -> bool:
    if utf8_len > 4 or len(text) != 1:
        return False
    return _is_han_scalar(ord(text[0]))


def _is_fully_spelled_reading(reading_bytes: bytes) -> bool:
    vowel = False
    for b in reading_bytes:
        if 49 <= b <= 53:  # '1'..'5'
            if not vowel:
                return False
            vowel = False
        elif b in _VOWELS:
            vowel = True
    return True


def compile_mimo_bin(
    pinyin: bool,
    sorted_entries: list[tuple[tuple[str, str, str, str], int]],
    sorted_context: list[tuple[str, int]],
) -> bytes:
    """Serialize sorted lexicon rows and context phrases into a MIMO-BIN-1 image."""
    storage_parts: list[bytes] = []
    storage_len = 0

    reading_offset = array.array("I")
    reading_len = array.array("H")
    hant_offset = array.array("I")
    hant_len = array.array("H")
    hans_offset = array.array("I")
    hans_len = array.array("H")
    weight_col = array.array("h")
    flags_col = bytearray()

    key_offset = array.array("I")
    key_len = array.array("H")
    word_start = array.array("I")
    word_end = array.array("I")

    phrase_offset = array.array("I")
    phrase_len = array.array("H")
    frequency_col = array.array("h")

    verified_single_syllables: set[str] = set()
    if pinyin:
        for (_, reading, hant, _), _ in sorted_entries:
            if len(hant) == 1 and len(hant.encode("utf-8")) <= 4 and _is_han_scalar(ord(hant)):
                verified_single_syllables.add(reading)

    last_bare: str | None = None
    longest = 0

    for word_idx, ((bare, reading, hant, hans), weight) in enumerate(sorted_entries):
        if last_bare != bare:
            bare_b = bare.encode("utf-8")
            b_len = len(bare_b)
            key_offset.append(storage_len)
            key_len.append(b_len)
            word_start.append(word_idx)
            word_end.append(word_idx + 1)
            storage_parts.append(bare_b)
            storage_len += b_len
            if b_len > longest:
                longest = b_len
            last_bare = bare
        else:
            word_end[-1] = word_idx + 1

        read_b = reading.encode("utf-8")
        r_off = storage_len
        r_len = len(read_b)
        storage_parts.append(read_b)
        storage_len += r_len

        hant_b = hant.encode("utf-8")
        t_off = storage_len
        t_len = len(hant_b)
        storage_parts.append(hant_b)
        storage_len += t_len

        if hans == hant:
            s_off = t_off
            s_len = t_len
        else:
            hans_b = hans.encode("utf-8")
            s_off = storage_len
            s_len = len(hans_b)
            storage_parts.append(hans_b)
            storage_len += s_len

        f = 0
        if _is_fully_spelled_reading(read_b):
            f |= FLAG_FULLY_SPELLED
        if _is_single_han(hant, t_len):
            f |= FLAG_SINGLE_HAN_HANT
        if _is_single_han(hans, s_len):
            f |= FLAG_SINGLE_HAN_HANS
        if not pinyin:
            f |= FLAG_VERIFIED_SPELLING
        else:
            syls = _SYLLABLE_RE.findall(reading)
            if syls and sum(len(s) for s in syls) == len(reading) and all(
                s in verified_single_syllables for s in syls
            ):
                f |= FLAG_VERIFIED_SPELLING

        reading_offset.append(r_off)
        reading_len.append(r_len)
        hant_offset.append(t_off)
        hant_len.append(t_len)
        hans_offset.append(s_off)
        hans_len.append(s_len)
        clamped_w = max(-32768, min(32767, int(weight)))
        weight_col.append(clamped_w)
        flags_col.append(f)

    for phrase, freq in sorted_context:
        phrase_b = phrase.encode("utf-8")
        p_len = len(phrase_b)
        phrase_offset.append(storage_len)
        phrase_len.append(p_len)
        clamped_f = max(-32768, min(32767, int(freq)))
        frequency_col.append(clamped_f)
        storage_parts.append(phrase_b)
        storage_len += p_len

    if sys.byteorder != "little":
        for arr in (
            reading_offset, reading_len, hant_offset, hant_len, hans_offset, hans_len,
            weight_col, key_offset, key_len, word_start, word_end, phrase_offset,
            phrase_len, frequency_col,
        ):
            arr.byteswap()

    meta = (1 if pinyin else 0) | (min(255, longest) << 8)
    header = struct.pack(
        "<12sIIIII",
        MIMO_BIN_MAGIC,
        meta,
        storage_len,
        len(reading_offset),
        len(key_offset),
        len(phrase_offset),
    )
    storage_blob = b"".join(storage_parts)
    return b"".join([
        header,
        storage_blob,
        reading_offset.tobytes(),
        reading_len.tobytes(),
        hant_offset.tobytes(),
        hant_len.tobytes(),
        hans_offset.tobytes(),
        hans_len.tobytes(),
        weight_col.tobytes(),
        bytes(flags_col),
        key_offset.tobytes(),
        key_len.tobytes(),
        word_start.tobytes(),
        word_end.tobytes(),
        phrase_offset.tobytes(),
        phrase_len.tobytes(),
        frequency_col.tobytes(),
    ])


def pack_mimo_base(pinyin_bin: bytes, zhuyin_bin: bytes) -> bytes:
    """Pack Pinyin and Zhuyin MIMO-BIN-1 blobs into a 64-byte aligned MIMO-BASE-1 container."""
    py_offset = 64
    py_len = len(pinyin_bin)
    after_py = py_offset + py_len
    zy_offset = (after_py + 63) & ~63
    zy_len = len(zhuyin_bin)
    header = struct.pack(
        "<12sIIIII",
        MIMO_BASE_MAGIC,
        1,
        py_offset,
        py_len,
        zy_offset,
        zy_len,
    )
    pad1 = b"\x00" * (py_offset - len(header))
    pad2 = b"\x00" * (zy_offset - after_py)
    return b"".join([header, pad1, pinyin_bin, pad2, zhuyin_bin])


def write_mimo_binaries(
    output_dir: pathlib.Path,
    pinyin_entries: list[tuple[tuple[str, str, str, str], int]],
    zhuyin_entries: list[tuple[tuple[str, str, str, str], int]],
    sorted_context: list[tuple[str, int]],
) -> dict[str, int]:
    """Write the unified mimo_base.bin (MIMO-BASE-1) binary lexicon container."""
    py_bin = compile_mimo_bin(True, pinyin_entries, sorted_context)
    zy_bin = compile_mimo_bin(False, zhuyin_entries, sorted_context)
    base_bin = pack_mimo_base(py_bin, zy_bin)
    for redundant in ("native-pinyin.bin", "native-zhuyin.bin"):
        p = output_dir / redundant
        if p.exists():
            p.unlink()
    (output_dir / "mimo_base.bin").write_bytes(base_bin)
    return {
        "pinyin_bin_bytes": len(py_bin),
        "zhuyin_bin_bytes": len(zy_bin),
        "mimo_base_bin_bytes": len(base_bin),
    }
