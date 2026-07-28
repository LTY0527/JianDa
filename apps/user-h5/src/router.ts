import { createRouter, createWebHistory } from "vue-router";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: () => import("./views/HomeView.vue"), meta: { title: "首页", showBack: false } },
    { path: "/listen", component: () => import("./views/ListenView.vue"), meta: { title: "听一听", showBack: false } },
    { path: "/news", component: () => import("./views/NewsFeedView.vue"), meta: { title: "权威资讯", showBack: true, backTo: "/" } },
    { path: "/assistant", component: () => import("./views/AssistantView.vue"), meta: { title: "简达助手", showBack: false } },
    { path: "/services", component: () => import("./views/ServicesView.vue"), meta: { title: "办事专区", showBack: false } },
    { path: "/profile", component: () => import("./views/ProfileView.vue"), meta: { title: "我的", showBack: false } },
    { path: "/category/:name", component: () => import("./views/ListView.vue"), meta: { title: "内容分类", showBack: true, backTo: "/news" } },
    { path: "/search", component: () => import("./views/ListView.vue"), meta: { title: "搜索", showBack: true, backTo: "/" } },
    { path: "/guide/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办事指南", showBack: true, backTo: "/services" } },
    { path: "/news/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "权威资讯", showBack: true, backTo: "/news" } },
    { path: "/steps/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办理步骤", showBack: true, backTo: "/services" } },
    { path: "/favorites", component: () => import("./views/FavoritesView.vue"), meta: { title: "我的收藏", showBack: true, backTo: "/profile" } },
    { path: "/history", component: () => import("./views/HistoryView.vue"), meta: { title: "历史浏览", showBack: true, backTo: "/profile" } },
    { path: "/assistant/history", component: () => import("./views/AssistantHistoryView.vue"), meta: { title: "历史会话", showBack: true, backTo: "/assistant" } },
    { path: "/settings", component: () => import("./views/SettingsView.vue"), meta: { title: "阅读设置", showBack: true, backTo: "/profile" } },
    { path: "/original/:slug", component: () => import("./views/OriginalView.vue"), meta: { title: "查看原文", showBack: true }, beforeEnter: (to) => { const slug = String(to.params.slug); const kind = to.query.from === "news" ? "news" : "guide"; to.meta.backTo = `/${kind}/${slug}`; } },
    { path: "/original-file/:slug", component: () => import("./views/OriginalFileView.vue"), meta: { title: "查看原文件", showBack: true }, beforeEnter: (to) => { const slug = String(to.params.slug); const kind = to.query.from === "news" ? "news" : "guide"; to.meta.backTo = `/${kind}/${slug}`; } },
  ],
});
export default router;
