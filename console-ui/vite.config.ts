import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })]
    })
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (
            id.includes('/node_modules/vue/')
            || id.includes('/node_modules/vue-router/')
            || id.includes('/node_modules/pinia/')
            || id.includes('/node_modules/@vue/')
          ) {
            return 'vendor-vue'
          }
          if (id.includes('/node_modules/axios/')) {
            return 'vendor-http'
          }
          if (id.includes('/node_modules/dayjs/')) {
            return 'vendor-date'
          }
          if (id.includes('/node_modules/zrender/')) {
            return 'vendor-zrender'
          }
          if (id.includes('/node_modules/echarts/')) {
            return 'vendor-echarts'
          }
        }
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  resolve: {
    alias: {
      '@': '/src'
    }
  }
})
