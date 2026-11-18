# 百爪典藏 · Final imagegen prompt set

使用內建 `image_gen`，所有最終資產均要求透明背景。以下為正式素材的生成規格。兩張角色圖集以舊款 `hero.png` 作毛色／貓種／雕塑質感參考；最終雙貓主視覺使用這兩張新圖集作參考。

## Pose sheets — shared prompt

```text
Use case: stylized-concept. FINAL PRODUCTION GAME SPRITE SHEET for a premium Meowpuut keyboard 100-sales commemorative skin. EXACT 2 by 2 grid on a 1024x1024 genuinely transparent canvas, four fully isolated complete cat characters. Each cell exactly512x512, no dividers, no labels, no text. One cat in each cell; four poses of the SAME character, consistent outfit, physical proportions, camera/frontal mild3D view, scale and lighting. Each character entirely contained in its own cell with at least48px transparent margins; paws at the same baseline about448px from each celltop, ears around65px, bodyheightabout370px. All tails, feet, cape edges must stay inside owncell. Polished high-end sculpted vinyl toy style with veryfine mattegrain, subtle soft champagne highlights, thick readable silhouette, clean transparent edges, no backdrop/floor/contact shadow. This sheet will be cut into four square cells, so keep strict cell separation. Referenceimage establishes only the species/color identities and sculptural art quality. Expressions and poses MUST be completely new, not copied from the reference. No keyboard, no extra cats, no panels or decorations between cells.
```

實際生成尺寸為 1254 × 1254，以實際尺寸的四個 627 × 627 圖格匯出。

## Mimimo — appended to shared prompt

```text
Subject: MIMIMO only, the charcoal-black cat with GOLDEN yellow eyes. NEW celebratory formal costume: fitted warm ivory sleeveless tuxedo waistcoat with two tiny brushed gold buttons, narrow midnight-blue necktie, tiny paw-shaped gold brooch near left lapel. Sleeves must never cover the cat's paws; keep cat paws distinct. Round black ears and dark curledtail. Four NEW full-body poses, rowmajor: TOP LEFT idle/host: sitting proudly upright, eyes wideopen and warmly smiling, onepaw over heart, otherpaw restingon knee, open celebratory welcoming expression ratherthan old sleepyeyes. TOP RIGHT bow: courteous genuine deepbow fromwaist, head lowered, eyes gentlyclosed, BOTH paws meetingover chest, tail liftedinsoftcurve. BOTTOM LEFT cheer: delighted broadclosedeye smile, bothfrontpaws raisedoutwardhigh, smallfeet together, dynamicupward posture yet allfullyinframe. BOTTOM RIGHT doze: curledseated hugging one smallgoldcommemorative roundmedal tightlyagainstchest, eyesclosed in contentment, headtilted toleft, tailwrappedaroundfront. Medal has a tiny pawrelief and NO letters or numerals (avoid tiny illegible UI text). Keep outfit identicalthroughallfourposes, and make expressions/actions unmistakablydifferent.
```

## Momo — appended to shared prompt

```text
Subject: MOMO only, the cream-colored Siamese cat with chocolate-brown ears, mask, paws and tail, PALE BLUE eyes. NEW celebratory outfit: an elegant short midnight-blue cape draped over shoulders, thin champagnegold piping, largechampagnegold silk bow atthe neck with a small goldpaw-shaped clasp, simple fabric with weight and folds. Four NEW full-body poses,rowmajor: TOP LEFT idle/host: seated with happywideopenblueeyes, mischievousconfident little smile, rightfrontpaw raisedwithpalmout in welcoming salute, tailcurling upone side. TOP RIGHT bow: sweet deepcourtesybow with headlowered and smilingclosedeyes, onepawtoheart and onepawextendedlike presentingthegift, navycape following bow naturally. BOTTOM LEFT cheer: joyfulclosedeye crescent smile, bothpawsraised overhead in awideV, littlebody leaningtooneside asif celebrating, cape slightlyflared butentirelyinsideowncell. BOTTOM RIGHT doze: restfulcurledsit hugging one smallgoldcommemorative medal inbothpaws, eyesclosed, cheekrestingonmedal, tailwrappedaroundfront; medal hasa tinypawrelief and NO lettering ornumerals. Keep outfit identicalthroughallfourposes and allfouractionsrecognizablydifferent.
```

## Final duo — hundred-paws-duo.png

```text
Use case: stylized-concept. Create the FINAL polished transparent collector-edition duo hero artwork for Meowpuut Hundred Paws, celebrating100sales. Landscape1536x1024 composition. The two reference sheets show the exact newlydesigned costumes andexpressions ofMimimo andMomo: use those character designsfaithfully, notoldplaincats. LeftblackcatMimimo wearsivorytuxedowaistcoat withgoldbuttons, narrowmidnightbluetie andgoldpawlapelbrooch. RightcreamSiameseMomo wearsmidnightblueshortcapewithgoldpiping, largechampagnebowwithpawclasp. Newjoyful pose: the blackcat hassparklingwideopengoldeneyes andproudsmile, holdsonepawtoheart; theSiamese hassmilingclosedcrescenteyesandopenhappylittlemouth, raisesonepawtocelebrate; innerpawsjoininthecenter asagentlefistbump overanexquisitestand-alonechampagnegoldmedallion. Bothfullbodies sittingclose, visiblefeetandtails entirelyinframe. Medalbetweenfrontfeet hasonecrispembossednumeral '100' andasmallpawengravingabove, thindoublerim, brushedgoldmetal, noothertext. Everycomponentbelongsinasinglecoherent3Dsculptedvinylcollectiblerenderwithtinyfinemattegrainandrefinedsilkfabricfolds. Warmsoftstudiolight, subtlechampagneedgehighlights, correctcontactbetweenpaws, nocroppedears/feet/tails. Generoustransparentmarginallaround; transparentbackgroundwithcrisphighqualityalphaedges, nopaintedbackground, nobackgroundhaze, noglow, nofloor. Restrainedpremiumcelebration, notcluttered: noballoons, confetti, cake, banners, extraobjectsorcats, noUIorChineselettering, nowatermark. Makefaces, clothes, pawfistbumpandmedalclearlyreadableat200pxwidth.
```
