import { createRouter, createWebHistory } from "vue-router";

const PUBLIC_PATHS = new Set([
  "/resident/login",
  "/resident/register",
]);

function isLoggedIn(): boolean {
  const token = localStorage.getItem("jianda_resident_token");
  return !!token;
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: () => import("./views/HomeView.vue"), meta: { title: "首页", showBack: false, requiresAuth: true } },
    { path: "/listen", component: () => import("./views/ListenView.vue"), meta: { title: "听一听", showBack: false, requiresAuth: true } },
    { path: "/neighborhood", component: () => import("./views/NeighborhoodView.vue"), meta: { title: "邻里", showBack: false, requiresAuth: true } },
    { path: "/resident/login", component: () => import("./views/ResidentLoginView.vue"), meta: { title: "登录", showBack: false, requiresAuth: false, publicPage: true } },
    { path: "/resident/register", component: () => import("./views/ResidentRegisterView.vue"), meta: { title: "注册", showBack: false, requiresAuth: false, publicPage: true } },
    { path: "/news", component: () => import("./views/NewsFeedView.vue"), meta: { title: "权威资讯", showBack: true, backTo: "/", requiresAuth: true } },
    { path: "/assistant", component: () => import("./views/AssistantView.vue"), meta: { title: "简达助手", showBack: false, requiresAuth: true } },
    { path: "/services", component: () => import("./views/ServicesView.vue"), meta: { title: "服务", showBack: false, requiresAuth: true } },
    { path: "/services/health", component: () => import("./views/ServiceChannelView.vue"), meta: { title: "社区卫生", serviceKind: "health", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/services/meals", component: () => import("./views/ServiceChannelView.vue"), meta: { title: "长者食堂", serviceKind: "meals", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/services/contacts", component: () => import("./views/ServiceChannelView.vue"), meta: { title: "便民电话", serviceKind: "contacts", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/services/guides", component: () => import("./views/ServiceChannelView.vue"), meta: { title: "办事指南", serviceKind: "guides", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/activities", component: () => import("./views/ServiceChannelView.vue"), meta: { title: "活动报名", serviceKind: "activities", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/trusted-services", component: () => import("./views/TrustedServicesView.vue"), meta: { title: "合作服务", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/orders", component: () => import("./views/ServiceOrdersView.vue"), meta: { title: "我的订单", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/membership", component: () => import("./views/MembershipView.vue"), meta: { title: "简达会员", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/profile", component: () => import("./views/ProfileView.vue"), meta: { title: "我的", showBack: false, requiresAuth: true } },
    { path: "/category/:name", component: () => import("./views/ListView.vue"), meta: { title: "内容分类", showBack: true, backTo: "/news", requiresAuth: true } },
    { path: "/search", component: () => import("./views/ListView.vue"), meta: { title: "搜索", showBack: true, backTo: "/", requiresAuth: true } },
    { path: "/guide/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办事指南", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/news/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "权威资讯", showBack: true, backTo: "/news", requiresAuth: true } },
    { path: "/steps/:slug", component: () => import("./views/DetailView.vue"), meta: { title: "办理步骤", showBack: true, backTo: "/services", requiresAuth: true } },
    { path: "/favorites", component: () => import("./views/FavoritesView.vue"), meta: { title: "我的收藏", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/history", component: () => import("./views/HistoryView.vue"), meta: { title: "历史浏览", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/reminders", component: () => import("./views/RemindersView.vue"), meta: { title: "我的提醒", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/assistant/history", component: () => import("./views/AssistantHistoryView.vue"), meta: { title: "历史会话", showBack: true, backTo: "/assistant", requiresAuth: true } },
    { path: "/settings", component: () => import("./views/SettingsView.vue"), meta: { title: "阅读设置", showBack: true, backTo: "/profile", requiresAuth: true } },
    { path: "/original/:slug", component: () => import("./views/OriginalView.vue"), meta: { title: "查看原文", showBack: true, requiresAuth: true }, beforeEnter: (to) => { const slug = String(to.params.slug); const kind = to.query.from === "news" ? "news" : "guide"; to.meta.backTo = `/${kind}/${slug}`; } },
    { path: "/original-file/:slug", component: () => import("./views/OriginalFileView.vue"), meta: { title: "查看原文件", showBack: true, requiresAuth: true }, beforeEnter: (to) => { const slug = String(to.params.slug); const kind = to.query.from === "news" ? "news" : "guide"; to.meta.backTo = `/${kind}/${slug}`; } },
  ],
});

router.beforeEach((to) => {
  const requiresAuth = to.meta.requiresAuth !== false;
  if (requiresAuth && !isLoggedIn()) {
    const redirect = to.fullPath;
    return {
      path: "/resident/login",
      query: redirect && redirect !== "/" ? { redirect } : {},
    };
  }
  if (to.meta.publicPage && isLoggedIn() && to.path === "/resident/login") {
    const redirect = typeof to.query.redirect === "string" && to.query.redirect.startsWith("/")
      ? to.query.redirect
      : "/";
    return { path: redirect };
  }
});

export default router;
