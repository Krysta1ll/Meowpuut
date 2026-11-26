import { defineConfig } from "vite";

// Tauri expects a fixed port and does not want the screen cleared. The pages import the cats straight from the
// repository's assets/ (and the pixel face from Android/), so the dev server may read one level up.
export default defineConfig({
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    fs: { allow: [".."] },
    watch: { ignored: ["**/src-tauri/**"] },
  },
  envPrefix: ["VITE_", "TAURI_ENV_"],
  build: {
    target: process.env.TAURI_ENV_PLATFORM === "windows" ? "chrome105" : "safari15",
    outDir: "dist",
    emptyOutDir: true,
    assetsInlineLimit: 0,
  },
});
