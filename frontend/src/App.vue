<script setup>
// 前端根组件，按照当前页面地址渲染路由对应的页面。
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<!-- 根组件只渲染当前路由对应的内容，页面布局由子路由决定。 -->
<!-- 应用根节点只提供路由出口。公开页和登录后的布局由路由树选择，所以切换页面无需重新创建 Vue 应用。 -->
<template>
  <el-config-provider :locale="zhCn"><router-view /></el-config-provider>
</template>
