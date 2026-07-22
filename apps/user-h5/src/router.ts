import { createRouter, createWebHistory } from "vue-router";
import HomeView from "./views/HomeView.vue";
import ListView from "./views/ListView.vue";
import DetailView from "./views/DetailView.vue";
import FavoritesView from "./views/FavoritesView.vue";
import SettingsView from "./views/SettingsView.vue";
import OriginalView from "./views/OriginalView.vue";
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: HomeView },
    { path: "/category/:name", component: ListView },
    { path: "/search", component: ListView },
    { path: "/guide/:slug", component: DetailView },
    { path: "/news/:slug", component: DetailView },
    { path: "/steps/:slug", component: DetailView },
    { path: "/favorites", component: FavoritesView },
    { path: "/settings", component: SettingsView },
    { path: "/original/:slug", component: OriginalView },
  ],
});
