<script setup>
// 提交当前密码与新密码，处理用户的密码修改。
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api/modules'
import { authState, clearSession } from '../stores/auth'

const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const formRef = ref()
const saving = ref(false)
// 确认框仅用于前端比对，实际请求只发送当前密码与新密码。后端仍会再次验证密码规则。
const rules = {
  currentPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 10, message: '密码至少 10 个字符', trigger: 'blur' }],
  confirmPassword: [{ validator: (_, value, callback) => callback(value !== form.newPassword || !value ? new Error('两次输入的密码不一致') : undefined), trigger: 'blur' }],
}
// 先检查密码表单，再提交修改。成功后清除当前登录状态并返回登录页。
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    await authApi.changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword })
    // 后端改密已经注销本次会话。这里同步清理前端身份和令牌，再带用户返回登录页。
    clearSession()
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await router.replace('/login')
  } catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
</script>

<!-- 表单绑定原密码、新密码和确认值，提交时由 save 统一处理。 -->
<template>
  <div><div class="page-heading"><div><span class="eyebrow">个人账号 / ACCOUNT</span><h1>账号设置</h1><p>{{ authState.user?.displayName }} · {{ authState.user?.username }}</p></div></div>
    <section class="panel account-panel"><div class="panel-heading"><div><h2>修改密码</h2><p>密码至少 10 个字符，包含字母和数字，UTF-8 长度不超过 72 字节。修改后所有旧登录失效。</p></div></div><el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="save"><el-form-item label="原密码" prop="currentPassword"><el-input v-model="form.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item><el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item><el-form-item label="确认新密码" prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" /></el-form-item><el-button type="primary" native-type="submit" :loading="saving">保存新密码</el-button></el-form></section>
  </div>
</template>
