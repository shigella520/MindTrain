import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.MINDTRAIN_CORE_URL || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/actuator/core': {
        target: process.env.MINDTRAIN_CORE_URL || 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: () => '/actuator/health',
      },
    },
  },
  preview: {
    port: 4173,
  },
  test: {
    environment: 'jsdom',
  },
})
