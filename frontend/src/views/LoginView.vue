<script setup>
// 提交普通账号登录表单，并提供本机免密账号选择。
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../stores/auth'
import LocalAccountSwitcher from '../components/LocalAccountSwitcher.vue'

const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
// 校验登录表单，成功后优先回到登录前请求的系统页面。
async function submit() {
  if (!form.username.trim() || !form.password) return ElMessage.warning('请输入账号和密码')
  submitting.value = true
  try {
    await login({ username: form.username.trim(), password: form.password })
    // 登录成功后只接受以单个斜杠开头的站内路径。有问题的跳转参数回退到工作台。
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/dashboard')
  } catch (error) {
    ElMessage.error(error.message)
  } finally { submitting.value = false }
}
</script>

<!-- 登录区包括本机免密账号选择和账号密码表单。 -->
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="story-brand"><span class="brand-symbol">选</span>学生选课系统</div>
      <div class="story-content">
        <span class="eyebrow">让学习，有序发生</span>
        <h1>从一门好课，<br />开启新的学期。</h1>
        <p>连接课程、教师与学生，<br />让每一次选择都有清晰的方向。</p>
        <div class="story-lines"><span>01 发现课程</span><span>02 规划学习</span><span>03 管理教学</span></div>
      </div>
      <small class="story-footer">WEB 项目实习 / 2026</small>
    </section>
    <section class="login-form-section">
      <div class="login-card">
        <span class="eyebrow">欢迎回来</span>
        <h2>登录选课系统</h2>
        <p class="muted">使用你的学生、教师或管理员账号继续。</p>
        <LocalAccountSwitcher />
        <el-form label-position="top" :model="form" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="form.username" name="username" placeholder="请输入学号、工号或管理员账号" autocomplete="username" size="large" maxlength="50" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" name="password" type="password" placeholder="请输入密码" autocomplete="current-password" show-password size="large" maxlength="100" />
          </el-form-item>
          <el-button type="primary" native-type="submit" size="large" :loading="submitting" class="login-submit">登录系统 <span aria-hidden="true">→</span></el-button>
        </el-form>
        <p class="login-help">账号由管理员分配，如需帮助请联系教学管理人员。</p>
      </div>
    </section>
  </div>
</template>
