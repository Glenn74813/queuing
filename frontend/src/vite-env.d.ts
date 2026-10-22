/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_AGENCY_NAME?: string;
  readonly VITE_AGENCY_LOGO?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
