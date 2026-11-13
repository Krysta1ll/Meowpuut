# macOS candidate baseline

The client checks are in `Sources/SmokeMixed.swift`. They compare the entire
candidate list, including the source and Japanese flag. The standalone core
has its own Pinyin and Zhuyin tests in `Engine/Tests/golden`.

```sh
bash scripts/build.sh --test
```

`MEOWPUUT_GOLDEN_CHECK=strict` requires identical lists. The default test
allows extra Japanese candidates when the Chinese list stays the same.
Update the baseline after an intentional change to the macOS candidate list.

```sh
MEOWPUUT_GOLDEN_WRITE=1 .build/Meowpuut.app/Contents/MacOS/Meowpuut --smoke
```
