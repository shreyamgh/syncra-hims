import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// In development the Vite server proxies the API and the dictation WebSocket to the
// gateway, so the browser stays same-origin (no CORS, mic permission on localhost).
const gateway = process.env.SYNCRA_GATEWAY_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: gateway, changeOrigin: true },
      '/ws': { target: gateway.replace(/^http/, 'ws'), ws: true, changeOrigin: true },
    },
  },
  build: { sourcemap: false, target: 'es2022' },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
