import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const proxy = {
    '/api': {
      target: env.API_PROXY_TARGET || 'http://127.0.0.1:8092',
      changeOrigin: true,
    },
  }

  return {
    plugins: [vue()],
    server: { host: '127.0.0.1', port: 5182, strictPort: true, proxy },
    preview: { host: '127.0.0.1', proxy },
  }
})
