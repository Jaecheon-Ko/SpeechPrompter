import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";

// 빌드 결과는 안드로이드 앱 assets/www 로 들어가 APK 안에서 바로 실행된다
export default defineConfig({
  base: "./",
  plugins: [react()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  build: {
    outDir: "../app/src/main/assets/www",
    emptyOutDir: true,
    target: "es2019",
  },
});
