import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const staticOutput = fileURLToPath(new URL('../src/main/resources/static', import.meta.url));

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: staticOutput,
    emptyOutDir: true,
  },
});
