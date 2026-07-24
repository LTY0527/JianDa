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
        configure(proxy) {
          proxy.on("proxyReq", (proxyRequest) => {
            // The browser talks to Vite same-origin. Do not forward its LAN Origin
            // into Spring CORS processing for this trusted development proxy hop.
            proxyRequest.removeHeader("origin");
          });
        },
      },
    },
  },
});
