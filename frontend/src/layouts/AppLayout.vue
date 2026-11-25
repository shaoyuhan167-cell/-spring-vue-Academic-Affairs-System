<script setup>
// 登录后共用的页面外壳，按角色展示导航，并提供账号操作入口。
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { navigation } from '../router'
import { authState, logout } from '../stores/auth'
import LocalAccountSwitcher from '../components/LocalAccountSwitcher.vue'

const route = useRoute()
const router = useRouter()
const leaving = ref(false)
const menuOpen = ref(false)
const roleNames = { ADMIN: '管理员', STUDENT: '学生', TEACHER: '教师' }
// 侧边栏只取当前角色允许访问的菜单项。
// 路由配置是导航项的共同来源。登录身份变化后，计算属性会重新筛选菜单，不会显示旧角色能访问的入口。
const menu = computed(() => navigation.filter((item) => item.roles.includes(authState.user?.role)))

// 调用后端退出接口，再回到登录页。
async function handleLogout() {
  leaving.value = true
  // 先让后端结束会话，再清除客户端数据。如果请求失败，保留当前页面以便用户重试。
  try {
    await logout()
    await router.replace('/login')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    leaving.value = false
  }
}
</script>

<!-- 登录后的页面由侧边导航、账号操作和当前路由内容组成。 -->
<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <router-link to="/dashboard" class="brand">
        <span class="brand-symbol">选</span>
        <span>学生选课系统<small>COURSE SELECTION</small></span>
      </router-link>
      <p class="nav-caption">教学与学习</p>
      <nav aria-label="主导航">
        <router-link v-for="item in menu" :key="item.path" :to="item.path" class="nav-item" @click="menuOpen = false">
          <span class="nav-mark">{{ item.mark }}</span>{{ item.title }}
        </router-link>
      </nav>
      <div class="sidebar-foot"><span class="status-dot"></span> 教学与选课管理</div>
    </aside>
    <button v-if="menuOpen" class="sidebar-mask" aria-label="关闭导航" @click="menuOpen = false"></button>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button class="mobile-toggle" aria-label="打开导航" @click="menuOpen = !menuOpen">☰</button>
          <span>选课系统</span><span class="breadcrumb-separator">/</span><strong>{{ route.meta.title }}</strong>
        </div>
        <div class="account-area">
          <span class="avatar">{{ authState.user?.displayName?.slice(0, 1) }}</span>
          <div class="account-info"><strong>{{ authState.user?.displayName }}</strong><small>{{ roleNames[authState.user?.role] }}</small></div>
          <LocalAccountSwitcher compact />
          <el-button text :loading="leaving" @click="handleLogout">退出</el-button>
        </div>
      </header>
      <main class="page-content"><router-view /></main>
      <footer class="page-footer">学生选课系统 · Web 项目实习 <span>Spring Boot / Vue</span></footer>
    </div>
  </div>
</template>
