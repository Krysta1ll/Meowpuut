// The cats, used exactly as drawn (FINAL.md: never redrawn, recoloured or pixelated by code). The clay figures and
// the hundred-paws pair come straight from the repository's assets/brand; the pixel avatars are the phone's, copied
// unchanged into ./art and only ever enlarged by whole numbers with nearest-neighbour sampling.

export const CAT = {
  mimimoPeek: new URL("../../assets/brand/cats/sprites/mimimo_edge_peek@2x.png", import.meta.url).href,
  momoPeek: new URL("../../assets/brand/cats/sprites/momo_edge_peek@2x.png", import.meta.url).href,
  mimimoIdle: new URL("../../assets/brand/cats/sprites/mimimo_idle@2x.png", import.meta.url).href,
  momoIdle: new URL("../../assets/brand/cats/sprites/momo_idle@2x.png", import.meta.url).href,
  mimimoHappy: new URL("../../assets/brand/cats/sprites/mimimo_happy@2x.png", import.meta.url).href,
  mimimoLookUp: new URL("../../assets/brand/cats/sprites/mimimo_look_up@2x.png", import.meta.url).href,
  mimimoSleep: new URL("../../assets/brand/cats/sprites/mimimo_sleep@2x.png", import.meta.url).href,
  momoHappy: new URL("../../assets/brand/cats/sprites/momo_happy@2x.png", import.meta.url).href,
  momoJump: new URL("../../assets/brand/cats/sprites/momo_jump@2x.png", import.meta.url).href,
  momoLookUp: new URL("../../assets/brand/cats/sprites/momo_look_up@2x.png", import.meta.url).href,
  momoWave: new URL("../../assets/brand/cats/sprites/momo_wave@2x.png", import.meta.url).href,
  mimimoLounge: new URL("../../assets/brand/cats/sprites/mimimo_edge_lounge@2x.png", import.meta.url).href,
  momoLounge: new URL("../../assets/brand/cats/sprites/momo_edge_lounge@2x.png", import.meta.url).href,
  mimimoCentenary: new URL("../../assets/brand/centenary/sprites/mimimo_centenary_idle@2x.png", import.meta.url).href,
  momoCentenary: new URL("../../assets/brand/centenary/sprites/momo_centenary_idle@2x.png", import.meta.url).href,
  mimimoPixel: new URL("./art/pixel_mimimo.png", import.meta.url).href,
  momoPixel: new URL("./art/pixel_momo.png", import.meta.url).href,
};

/** The pair a skin shows peeking over things: clay, the hundred-paws pair, or the pixel avatars (28 px, ×2). */
export function catPair(skin: string): { left: string; right: string; kind: "clay" | "centenary" | "pixel" } {
  if (skin === "centenary") return { left: CAT.mimimoCentenary, right: CAT.momoCentenary, kind: "centenary" };
  if (skin === "pixel" || skin === "glyph") return { left: CAT.mimimoPixel, right: CAT.momoPixel, kind: "pixel" };
  return { left: CAT.mimimoPeek, right: CAT.momoPeek, kind: "clay" };
}
