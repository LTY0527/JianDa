import { createApp } from "vue";
import "@jianda/shared-ui/tokens.css";
import "./styles.css";
import App from "./App.vue";
import router from "./router";

createApp(App).use(router).mount("#app");
