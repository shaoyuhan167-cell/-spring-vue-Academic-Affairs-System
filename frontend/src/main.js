// 创建 Vue 应用，加载路由、组件库和全局样式，再挂载到页面。
import { createApp } from 'vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElLoading, ElTable, ElTableColumn, ElTag, ElDialog, ElDatePicker, ElInputNumber, ElSelect, ElOption, ElSwitch, ElPagination, ElEmpty } from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'
import App from './App.vue'
import router from './router'

// 创建根组件，注册路由和界面组件库后挂载到页面。
const app = createApp(App)
app.use(router)
// 集中注册项目使用的 Element Plus 组件。加载指令另外注册，页面可直接使用 v-loading 显示等待状态。
for (const component of [ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElTable, ElTableColumn, ElTag, ElDialog, ElDatePicker, ElInputNumber, ElSelect, ElOption, ElSwitch, ElPagination, ElEmpty]) {
  app.use(component)
}
app.use(ElLoading)
app.mount('#app')
