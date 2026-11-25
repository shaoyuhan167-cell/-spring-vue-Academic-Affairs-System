// 编译 Vue 静态资源，供正式应用通过 Spring Boot 提供页面。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
})
