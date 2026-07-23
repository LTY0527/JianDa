import "vue-router";
export {};
declare module "vue-router" {
  interface RouteMeta {
    title: string;
    showBack: boolean;
    backTo?: string;
    platformOnly?: boolean;
  }
}