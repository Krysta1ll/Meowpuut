# Key face rendering bug (owner screenshot 2026-11-12 11.24.15)

Branch fast-forwarded to lexicon-without-rime-ice (95ade04).
Helper scripts (not committed) in .build/bug/: run.sh (real keyboard plan),
hostflip.sh (flip system appearance with the real keyboard up), lab.sh,
scripts.sh (lab touch scripts), contrast.sh, sample.py (key face colours).

Owner's key faces: flat (92,114,144), close to the ocean accent glass
(91,120,153 on a normal render). Labels black, bar light.

Tried on iOS 27.2 (iPhone 18 Pro), no repro: page switches with ^_^ lit,
nihao then symbols, expand/collapse panels, Increase Contrast on/off.

Found on 27.2: real keyboard opened in system dark, system flipped to light:
the keyboard stays dark (dark bar, white labels) over the light backdrop.
(.build/bug/hf27a/2-light.png). Not the owner's picture, but a fault.
Cause: refreshAppearance also counted textDocumentProxy.keyboardAppearance
== .dark; for a default field iOS reports the appearance the host last saw
(stale both while the keyboard is up and on the next opening). Fixed: the
system trait only (commit after this note). Verified on 27.2: after27-flip,
after27-reuse. Cost: a host that asks for a dark keyboard gets the system's
appearance.

Owner's picture: still not reproduced. Ruled out by measurement: dark-style
glass with light tint (227,230,234), light glass over black (149,174,200).
Closest match: the accent tint on every key (91,120,153 vs owner 86,115,147
in sRGB). Kaomoji "highlight" is the bar's left-end light, not a selection.
Left: 26.2 checks, default shots + frames, test-ios.sh, typing checks.
