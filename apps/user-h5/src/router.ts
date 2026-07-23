import { createRouter, createWebHistory } from "vue-router";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: () => import("./views/HomeView.vue"), meta: { title: "首页", showBack: false } },
    { path: "/category/:name", component: () => import("./views/ListView.vue"), meta: { title: "内容分类", showBack: false } },
    { path: "/search", component: () => import("./views/ListView.vue"), meta: { title: "搜索", showBack: true, backTo: "/" } },
    { path: "/guide/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办事指南", showBack: true, backTo: "/" } },
    { path: "/news/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "权威资讯", showBack: true, backTo: "/" } },
    { path: "/steps/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办理步骤", showBack: true, backTo: "/" } },
    { path: "/favorites", component: () => import("./views/FavoritesView.vue"), meta: { title: "我的收藏", showBack: false } },
    { path: "/settings", component: () => import("./views/SettingsView.vue"), meta: { title: "阅读设置", showBack: false } },
    {
      path: "/original/:slug",
      component: () => import("./views/OriginalView.vue"),
      meta: { title: "原始通知", showBack: true },
      beforeEnter: (to) => {
        if (!to.meta.backTo) {
          const slug = String(to.params.slug);
          to.meta.backTo = `/guide/${slug}`;
        }
      },
    },
  ],
});
export default router;