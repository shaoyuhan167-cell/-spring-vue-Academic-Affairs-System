<script setup>
// 加载账号资料，将可编辑的邮箱、电话和简介填入表单。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { learningApi } from '../api/modules'
import { dateTime } from '../utils/academic'
const profile = ref(null), loading = ref(false), saving = ref(false), error = ref(''), formRef = ref()
const form = reactive({ email: '', phone: '', bio: '' })
const roles = { ADMIN: '管理员', TEACHER: '教师', STUDENT: '学生' }
// 邮箱、联系电话与简介是可编辑字段。学号、工号、院系等信息从档案接口读取并仅展示。
const rules = { email: [{ type: 'email', message: '请输入正确的邮箱地址', trigger: 'blur' }], phone: [{ pattern: /^[0-9+()\- ]{0,30}$/, message: '请输入有效的联系电话', trigger: 'blur' }] }
// 获取完整资料，从中取出可编辑字段放入表单。
async function load() { loading.value = true; error.value = ''; try { profile.value = await learningApi.profile(); Object.assign(form, { email: profile.value.email, phone: profile.value.phone, bio: profile.value.bio }) } catch (exception) { error.value = exception.message } finally { loading.value = false } }
// 校验联系资料后提交，成功时使用接口返回的资料刷新页面。
async function save() { if (!await formRef.value?.validate().catch(() => false)) return; saving.value = true; try { profile.value = await learningApi.saveProfile({ ...form }); ElMessage.success('个人资料已保存') } catch (exception) { ElMessage.error(exception.message) } finally { saving.value = false } }
// 页面首次挂载时加载当前账号的个人资料。
onMounted(load)
</script>
<!-- 只读身份和学籍信息显示在资料顶部，联系方式及简介通过表单维护。 -->
<template><div><div class="page-heading"><div><span class="eyebrow">账户 / PROFILE</span><h1>个人资料</h1><p>维护联系方式和个人简介，学籍与任职信息由管理员维护。</p></div></div><el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" /><section class="panel profile-panel" v-loading="loading"><template v-if="profile"><div class="profile-identity"><div class="profile-avatar">{{ profile.displayName.slice(0, 1) }}</div><div><h2>{{ profile.displayName }}</h2><p class="muted">{{ roles[profile.role] }} · {{ profile.username }}</p></div></div><dl class="profile-facts"><template v-if="profile.role === 'STUDENT'"><div><dt>学号</dt><dd>{{ profile.studentNo }}</dd></div><div><dt>专业</dt><dd>{{ profile.major }}</dd></div><div><dt>班级</dt><dd>{{ profile.className }}</dd></div></template><template v-if="profile.role === 'TEACHER'"><div><dt>工号</dt><dd>{{ profile.teacherNo }}</dd></div><div><dt>院系</dt><dd>{{ profile.department }}</dd></div></template></dl><el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="form-grid"><el-form-item label="邮箱" prop="email"><el-input v-model="form.email" maxlength="120" placeholder="选填，用于联系" /></el-form-item><el-form-item label="联系电话" prop="phone"><el-input v-model="form.phone" maxlength="30" placeholder="选填" /></el-form-item><el-form-item label="个人简介" prop="bio" class="full-width"><el-input v-model="form.bio" type="textarea" :rows="5" maxlength="500" show-word-limit /></el-form-item></el-form><div class="grade-actions"><span class="muted">最近更新：{{ dateTime(profile.updatedAt) }}</span><el-button type="primary" :loading="saving" @click="save">保存资料</el-button></div></template></section></div></template>
