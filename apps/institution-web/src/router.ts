import { createRouter, createWebHistory } from "vue-router";
import { isPlatformAdmin } from "./auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: () => import("./views/LoginView.vue"), meta: { title: "机构登录", showBack: false } },
    {
      path: "/",
      component: () => import("./components/AppShell.vue"),
      children: [
        { path: "", component: () => import("./views/DashboardView.vue"), meta: { title: "工作台", showBack: false } },
        { path: "documents", component: () => import("./views/DocumentsView.vue"), meta: { title: "内容中心", showBack: false } },
        { path: "documents/upload", component: () => import("./views/UploadView.vue"), meta: { title: "上传材料", showBack: true, backTo: "/documents" } },
        { path: "documents/:id/process", component: () => import("./views/ProcessingView.vue"), meta: { title: "处理结果", showBack: true, backTo: "/documents" } },
        { path: "documents/:id/review", component: () => import("./views/ReviewView.vue"), meta: { title: "原文对照审核", showBack: true, backTo: "/documents" } },
        { path: "documents/:id/publish", component: () => import("./views/PublishView.vue"), meta: { title: "审核与发布", showBack: true, backTo: "/documents" } },
        { path: "published", component: () => import("./views/PublishedView.vue"), meta: { title: "已发布内容", showBack: false } },
        { path: "public-sources", component: () => import("./views/PublicSourcesView.vue"), meta: { title: "采集与来源", showBack: false, platformOnly: true } },
        { path: "public-import", component: () => import("./views/PublicImportView.vue"), meta: { title: "添加内容", showBack: true, backTo: "/documents", platformOnly: true } },
        { path: "operations", component: () => import("./views/OperationsView.vue"), meta: { title: "数据概览", showBack: false, platformOnly: true } },
        { path: "forbidden", component: () => import("./views/ForbiddenView.vue"), meta: { title: "无权访问", showBack: true, backTo: "/" } },
        { path: "logs", component: () => import("./views/LogsView.vue"), meta: { title: "系统记录", showBack: false } },
      ],
    },
  ],
});
router.beforeEach((to) => {
  if (to.path !== "/login" && !localStorage.getItem("jianda_token")) return "/login";
  if (to.path === "/login" && localStorage.getItem("jianda_token")) return "/";
  if (to.meta.platformOnly && !isPlatformAdmin()) return "/forbidden";
});
export default router;
