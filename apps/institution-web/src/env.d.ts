/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_H5_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
