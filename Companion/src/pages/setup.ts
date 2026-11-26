// 黃金 5 分鐘開箱序幕（Plan B 純遊戲首啟：零系統輸入法安裝、零管理員權限、零登出提示）。
// 1. 敲紙箱見莫莫探頭＋米米莫珍奶瞳 -> 2. 摸頭搔下巴得 CAT_PURR 與鈷藍瓶蓋 ->
// 3. 一鍵推窗上桌面（CAT_OUT）-> 4. 碰鼻互動＋免費 3 選 1 首件家具，直接進入小貓客廳。

import { api } from "../api";
import { CAT } from "../art";
import { esc, t } from "../i18n";
import { nav } from "../nav";
import { applySkin } from "../skins";
import { store } from "../store";

const STARTERS: { id: string; icon: string; name: [string, string]; desc: [string, string] }[] = [
  { id: "kibble_rug", icon: "🧶", name: ["貓豆豆編織毯", "猫豆豆编织毯"], desc: ["軟綿綿暖色圓毯，翻肚皮曬太陽剛剛好", "软绵绵暖色圆毯，翻肚皮晒太阳刚刚好"] },
  { id: "noodle_bed", icon: "🍜", name: ["泡麵盒專車窩", "泡面盒专车窝"], desc: ["莫莫認證最舒服的軟墊泡麵盒", "莫莫认证最舒服的软垫泡面盒"] },
  { id: "corn_pot", icon: "🌽", name: ["黃金甜玉米鍋", "黄金甜玉米锅"], desc: ["冒著甜香熱氣，米米莫最沒抵抗力", "冒着甜香热气，米米莫最没抵抗力"] },
];

export function renderSetup(app: HTMLElement): () => void {
  let step = 1; // 1..4
  let knocks = 0;
  let patCount = 0;
  let catsOut = true;
  let noseBooped = false;
  let pickedFurniture = "";
  let line = t("暖紙小屋中央放著一只微微晃動的快遞紙箱……輕點敲敲紙箱看看。", "暖纸小屋中央放着一只微微晃动的快递纸箱……轻点敲敲纸箱看看。");

  const box = document.createElement("div");
  box.className = "setup unbox";
  app.append(box);
  applySkin(box, "daily");

  const finish = async (skipped: boolean) => {
    await api.deskSetOut(catsOut).catch(() => {});
    store.companion = await api.updateCompanion({ firstRunDone: true, catsOut });
    nav.go(skipped ? "home" : "lounge");
    nav.refresh();
  };

  const draw = () => {
    let stageHtml = "";
    if (step === 1) {
      const catReveal =
        knocks === 0
          ? `<div class="unbox-crate wobble" data-knock><span class="crate-ico">📦</span><b>${t("敲敲紙箱", "敲敲纸箱")}</b></div>`
          : knocks === 1
            ? `<div class="unbox-crate" data-knock>
                 <img class="unbox-cat momo" src="${CAT.momoPeek}" alt="莫莫">
                 <b>${t("莫莫探頭啦！再敲一下看看箱子裡還有誰…", "莫莫探头啦！再敲一下看看箱子里还有谁…")}</b>
               </div>`
            : `<div class="unbox-crate open">
                 <img class="unbox-cat mimimo dilate" src="${CAT.mimimoLookUp}" alt="米米莫">
                 <img class="unbox-cat momo" src="${CAT.momoHappy}" alt="莫莫">
               </div>`;
      stageHtml = `
        <p class="nar">${esc(line)}</p>
        ${catReveal}
        ${knocks >= 2 ? `<button class="btn primary" data-next>${t("輕輕摸摸牠們 ›", "轻轻摸摸它们 ›")}</button>` : ""}`;
    } else if (step === 2) {
      stageHtml = `
        <p class="nar">${esc(line)}</p>
        <div class="unbox-duo">
          <button class="unbox-pet" data-pet="momo">
            <img src="${patCount > 0 ? CAT.momoHappy : CAT.momoIdle}" alt="莫莫">
            <span>${t("輕摸莫莫頭頂", "轻摸莫莫头顶")}</span>
          </button>
          <button class="unbox-pet" data-pet="mimimo">
            <img class="${patCount >= 2 ? "dilate" : ""}" src="${patCount >= 2 ? CAT.mimimoHappy : CAT.mimimoLookUp}" alt="米米莫">
            <span>${t("輕搔米米莫下巴", "轻搔米米莫下巴")}</span>
          </button>
        </div>
        ${patCount >= 2 ? `<p class="hint">🔵 ${t("莫莫從箱底叼出第一件奇珍「鈷藍星光瓶蓋」放在你手邊！", "莫莫从箱底叼出第一件奇珍「钴蓝星光瓶盖」放在你手边！")}</p><button class="btn primary" data-next>${t("幫牠們推開窗戶 ›", "帮它们推开窗户 ›")}</button>` : ""}`;
    } else if (step === 3) {
      stageHtml = `
        <p class="nar">${t("推開窗戶，讓米米莫和莫莫跳上你的桌面一起工作發呆吧！", "推开窗户，让米米莫和莫莫跳上你的桌面一起工作发呆吧！")}</p>
        <div class="unbox-duo">
          <img class="unbox-cat" src="${CAT.momoJump}" alt="莫莫">
          <img class="unbox-cat" src="${CAT.mimimoIdle}" alt="米米莫">
        </div>
        <p class="hint">${t("主視窗在前時雙貓回小屋陪你佈置；縮到系統匣時牠們會自動跳回桌面巡邏。無需任何系統權限。", "主窗口在前时双猫回小屋陪你布置；缩到系统托盘时它们会自动跳回桌面巡逻。无需任何系统权限。")}</p>
        <div class="row-btns">
          <button class="btn primary" data-push-window="1">${t("🪟 推窗讓牠們上桌面！", "🪟 推窗让它们上桌面！")}</button>
          <button class="btn" data-push-window="0">${t("先待在紙本小屋裡", "先待在纸本小屋里")}</button>
        </div>`;
    } else {
      stageHtml = `
        <p class="nar">${t("見面最後一步：輕點貓鼻碰個鼻，再免費挑 1 件地板家具擺進小屋！", "见面最后一步：轻点猫鼻碰个鼻，再免费挑 1 件地板家具摆进小屋！")}</p>
        <div class="unbox-duo">
          <button class="unbox-pet${noseBooped ? " booped" : ""}" data-nose>
            <img src="${noseBooped ? CAT.mimimoHappy : CAT.mimimoIdle}" alt="米米莫">
            <span>${noseBooped ? t("💗 碰鼻成功！米米莫害羞了一下", "💗 碰鼻成功！米米莫害羞了一下") : t("點這裡輕輕碰鼻", "点这里轻轻碰鼻")}</span>
          </button>
        </div>
        <div class="unbox-starters">
          ${STARTERS.map(
            (item) => `<button class="l-furn${pickedFurniture === item.id ? " on" : ""}" data-starter="${item.id}">
              <span class="lf-ico">${item.icon}</span>
              <span class="lf-txt"><b>${esc(t(...item.name))}</b><small>${esc(t(...item.desc))}</small></span>
            </button>`,
          ).join("")}
        </div>
        <div class="row-btns">
          <button class="btn primary" data-done ${pickedFurniture ? "" : "disabled"}>${t("搬進小貓客廳！", "搬进小猫客厅！")}</button>
        </div>`;
    }

    box.innerHTML = `
      <div class="setup-top">
        <span class="dots">${[1, 2, 3, 4].map((i) => `<i class="${i === step ? "on" : i < step ? "done" : ""}"></i>`).join("")}</span>
        <button class="skip" data-skip>${t("直接進入", "直接进入")}</button>
      </div>
      <div class="stage unbox-stage">${stageHtml}</div>`;
  };

  const onClick = async (e: MouseEvent) => {
    const el = e.target as HTMLElement;
    if (el.closest("[data-skip]")) return void finish(true);
    if (el.closest("[data-knock]")) {
      knocks++;
      line =
        knocks === 1
          ? t("沙沙——莫莫探出天藍大眼睛偷看你！再敲一次看看裡面還有誰。", "沙沙——莫莫探出天蓝大眼睛偷看你！再敲一次看看里面还有谁。")
          : t("米米莫探出半顆炭黑腦袋，對視瞬間爆出珍奶黑大圓瞳！", "米米莫探出半颗炭黑脑袋，对视瞬间爆出珍奶黑大圆瞳！");
      return draw();
    }
    const petBtn = el.closest<HTMLElement>("[data-pet]");
    if (petBtn) {
      const cat = petBtn.dataset.pet as "mimimo" | "momo";
      patCount++;
      await api.loungeInteract("pat", cat).catch(() => {});
      line =
        cat === "momo"
          ? t("莫莫立刻蹭進你掌心開呼嚕！試試輕搔米米莫的下巴。", "莫莫立刻蹭进你掌心开呼噜！试试轻搔米米莫的下巴。")
          : t("米米莫瞬間切換珍奶圓瞳開始低頻呼嚕：還、還算米到奧！", "米米莫瞬间切换珍奶圆瞳开始低频呼噜：还、还算米到奥！");
      return draw();
    }
    const winBtn = el.closest<HTMLElement>("[data-push-window]");
    if (winBtn) {
      catsOut = winBtn.dataset.pushWindow === "1";
      await api.deskSetOut(catsOut).catch(() => {});
      step = 4;
      return draw();
    }
    if (el.closest("[data-nose]")) {
      noseBooped = true;
      await api.loungeInteract("pat", "mimimo").catch(() => {});
      return draw();
    }
    const stBtn = el.closest<HTMLElement>("[data-starter]");
    if (stBtn) {
      pickedFurniture = stBtn.dataset.starter!;
      await api.loungeClaimStarter(pickedFurniture).catch(() => {});
      return draw();
    }
    if (el.closest("[data-next]")) {
      step = Math.min(4, step + 1);
      return draw();
    }
    if (el.closest("[data-done]")) {
      return void finish(false);
    }
  };

  box.addEventListener("click", onClick);
  draw();
  return () => box.removeEventListener("click", onClick);
}
