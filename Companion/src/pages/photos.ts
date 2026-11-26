// 生活照, the full page (FINAL.md §2.5): every photo in today's order. The photos come from the cache and appear as their
// files arrive; with no connection and nothing cached the page says so and nothing waits.

import { esc, t } from "../i18n";
import { fill, figure, lightbox, onPhotosUpdated, todaysPhotos, waiting } from "../photos";
import type { PhotoInfo } from "../api";

export function renderPhotos(app: HTMLElement): () => void {
  let stop = () => {};
  let list: PhotoInfo[] = [];
  const draw = async () => {
    const view = await todaysPhotos();
    if (!app.isConnected) return;
    list = view.photos;
    stop();
    app.innerHTML = `
      <header class="page-hd">
        <h1>${t("生活照", "生活照")}</h1>
        <span class="mut">${t("米米莫和莫莫的照片，每天換一個順序。", "米米莫和莫莫的照片，每天换一个顺序。")}</span>
      </header>
      ${list.length ? `<div class="wall">${list.map((p) => figure(p)).join("")}</div>` : `<p class="mut photos-wait">${esc(waiting(view.haveManifest))}</p>`}`;
    stop = fill(app, app);
  };
  const click = (e: Event) => {
    const fig = (e.target as HTMLElement).closest<HTMLElement>("figure.ph");
    const p = list.find((x) => x.file === fig?.dataset.file);
    if (!p || !(e.type === "click" || (e as KeyboardEvent).key === "Enter")) return;
    // 字景: the first click turns the type into the photo, the next opens it large.
    if (fig!.classList.contains("glyph") && !fig!.classList.contains("shown") && fig!.querySelector(".ph-type")) fig!.classList.add("shown");
    else lightbox(p);
  };
  app.addEventListener("click", click);
  app.addEventListener("keydown", click);
  const off = onPhotosUpdated(() => void draw());
  void draw();
  return () => {
    stop();
    off();
  };
}
