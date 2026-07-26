import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 5174,
    proxy: {
      "/api": {
        target: process.env.VITE_PROXY_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
        bypass(request) {
          // The browser talks to Vite same-origin. Remove its LAN Origin before
          // http-proxy constructs the trusted local request to Spring.
          delete request.headers.origin;
        },
      },
    },
  },
});
