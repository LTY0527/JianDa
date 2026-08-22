/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_H5_BASE_URL?: string;
  readonly VITE_ENABLE_DEV_FIXTURES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
