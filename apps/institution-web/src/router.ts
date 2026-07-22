import { createRouter, createWebHistory } from "vue-router";
import LoginView from "./views/LoginView.vue";
import AppShell from "./components/AppShell.vue";
import DashboardView from "./views/DashboardView.vue";
import DocumentsView from "./views/DocumentsView.vue";
import UploadView from "./views/UploadView.vue";
import ProcessingView from "./views/ProcessingView.vue";
import ReviewView from "./views/ReviewView.vue";
import PublishView from "./views/PublishView.vue";
import PublishedView from "./views/PublishedView.vue";
import PublicImportView from "./views/PublicImportView.vue";
import PublicSourcesView from "./views/PublicSourcesView.vue";
import ForbiddenView from "./views/ForbiddenView.vue";
import LogsView from "./views/LogsView.vue";
import { isPlatformAdmin } from "./auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: LoginView },
    {
      path: "/",
      component: AppShell,
      children: [
        { path: "", component: DashboardView },
        { path: "documents", component: DocumentsView },
        { path: "documents/upload", component: UploadView },
        { path: "documents/:id/process", component: ProcessingView },
        { path: "documents/:id/review", component: ReviewView },
        { path: "documents/:id/publish", component: PublishView },
        { path: "published", component: PublishedView },
        { path: "public-sources", component: PublicSourcesView, meta: { platformOnly: true } },
        { path: "public-import", component: PublicImportView, meta: { platformOnly: true } },
        { path: "forbidden", component: ForbiddenView },
        { path: "logs", component: LogsView },
      ],
    },
  ],
});
router.beforeEach((to) => {
  if (to.path !== "/login" && !localStorage.getItem("jianda_token"))
    return "/login";
  if (to.path === "/login" && localStorage.getItem("jianda_token")) return "/";
  if (to.meta.platformOnly && !isPlatformAdmin()) return "/forbidden";
});
export default router;
