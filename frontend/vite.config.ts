import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  // 使用 Vue 插件后，Vite 才能识别 .vue 单文件组件。
  plugins: [vue()],
})
