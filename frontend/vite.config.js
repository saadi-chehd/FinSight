import { defineConfig, loadEnv } from 'vite';
import { fileURLToPath } from 'node:url';
const envDir = fileURLToPath(new URL('../', import.meta.url));
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, envDir, 'VITE_');
  return {
    envDir,
    server: {
      host: '127.0.0.1', port: Number(env.VITE_PORT || 5173), strictPort: true,
      proxy: { '/api': { target: env.VITE_BACKEND_TARGET || 'http://127.0.0.1:8080' } },
    },
  };
});
