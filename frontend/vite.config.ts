/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies REST and STOMP/WebSocket traffic to the Spring Boot backend.
// QMS_API_TARGET (environment or .env.local) overrides the backend address, e.g. when port 8080
// is taken on the workstation.
export default defineConfig(({ mode }) => {
  const apiTarget = loadEnv(mode, '.', '').QMS_API_TARGET || 'http://localhost:8080';
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': { target: apiTarget, changeOrigin: true },
        '/ws': { target: apiTarget, ws: true, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  };
});
