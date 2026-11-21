#!/usr/bin/env node
// The journey of 字景 as the browser prototype walks it: writes the reference the unit tests compare the keyboard's journey with.
//
//   node scripts/gen_glyph_journey_parity.js design-preview/specs/glyph-scenes.template.html \
//        > Android/app/src/test/resources/glyph-journey-parity.json
//
// It runs the prototype's own `placeAt` and `h2` (their source is cut out of the template and evaluated as it stands), with the scenes' names
// and rarities read from the template's scene list. Nothing here is a second implementation of the rule.
const fs = require("fs");
const html = fs.readFileSync(process.argv[2], "utf8");
const grab = (re, what) => { const m = html.match(re); if (!m) throw new Error("not found in the template: " + what); return m[0]; };
const h2 = grab(/function h2\(x, y\) \{[^\n]*\}/, "h2");
const place = grab(/const PLACE = \[[^\]]*\];/, "PLACE");
const placeAt = html.slice(html.indexOf("function placeAt(n)"), html.indexOf("function travel()"));
if (!placeAt.includes("function placeAt")) throw new Error("placeAt not found");
const scenes = [...html.matchAll(/^  \{ name: "([^"]+)"([^\n]*)/gm)].map(m => {
  const own = m[2].split("front:")[0];
  const r = /rare: (true|[0-9.]+)/.exec(own);
  return r ? { name: m[1], rare: r[1] === "true" ? true : parseFloat(r[1]) } : { name: m[1] };
});
const run = new Function("SCENES", `${h2}\n${place}\nconst journey = { places: [] };\n${placeAt}\nreturn placeAt;`)(scenes);
const characters = 248316, units = characters * 8;
const places = [];
for (let n = 0; n < 600; n++) places.push({ scene: scenes[run(n).scene].name, len: run(n).len });
const visits = Object.fromEntries(scenes.map(s => [s.name, 0]));
let sum = 0, n = 0, stop = 0;
for (;; n++) { const p = run(n); visits[scenes[p.scene].name]++; sum += p.len; if (sum > units) { stop = n + 1; break; } }
process.stdout.write(JSON.stringify({
  note: "written by scripts/gen_glyph_journey_parity.js from the prototype's placeAt; do not edit by hand",
  scenes: scenes.map(s => s.name),
  places,
  tally: { characters, units, stop, visits },
}, null, 1) + "\n");
