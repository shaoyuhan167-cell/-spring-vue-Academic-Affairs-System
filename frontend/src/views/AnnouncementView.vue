<script setup>
// 同一组件支持读者查阅公告和管理员维护公告，具体功能由当前路由决定。
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { learningApi } from '../api/modules'
import { dateTime } from '../utils/academic'

const route = useRoute(), admin = computed(() => route.path === '/announcement-management')
const rows = ref([]), total = ref(0), page = ref(1), size = ref(10), keyword = ref(''), unread = ref(false)
const loading = ref(false), error = ref(''), saving = ref(false), dialog = ref(false), detail = ref(null), editingId = ref(null), formRef = ref()
const form = reactive({}), audiences = { ALL: '所有人', STUDENT: '学生', TEACHER: '教师', ADMIN: '管理员' }
const rules = { title: [{ required: true, whitespace: true, message: '请输入标题', trigger: 'blur' }], content: [{ required: true, whitespace: true, message: '请输入正文', trigger: 'blur' }] }
let requestVersion = 0
// 按角色选用公告接口，并传入当前页码、关键词和未读条件。
async function load() {
  // 读者页和管理页共用组件。请求编号阻止切换角色页面后仍在等待的旧列表改写新页面。
  const version = ++requestVersion; loading.value = true; error.value = ''
  try { const result = await learningApi.notices(admin.value, { page: page.value, size: size.value, keyword: keyword.value.trim() || undefined, unreadOnly: unread.value }); if (version === requestVersion) { rows.value = result.records; total.value = result.total } }
  catch (exception) { if (version === requestVersion) error.value = exception.message }
  finally { if (version === requestVersion) loading.value = false }
}
// 查询条件变化后回到第一页。
function search() { page.value = 1; load() }
// 读者列表和公告后台共用组件，切换路由时重置上个页面的筛选和弹窗。
watch(() => route.path, () => { rows.value = []; page.value = 1; keyword.value = ''; unread.value = false; dialog.value = false; detail.value = null; load() }, { immediate: true })
// 新公告使用默认值，编辑时带入当前版本供后端校验。
function openEditor(row = null) {
  editingId.value = row?.id ?? null
  // 新建公告默认保存为草稿，编辑已有公告时连同版本号一起带入表单。
  Object.assign(form, row ? { ...row } : { title: '', content: '', audience: 'ALL', status: 'DRAFT', pinned: false, visibleFrom: null, expiresAt: null, revision: 0 })
  dialog.value = true
}
// 检查公告标题和正文后，将新建或编辑的内容提交给后台。
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    // 去掉标题、正文首尾空白。未设置的展示时间传 null，让后端按没有时间限制处理。
    const body = { title: form.title.trim(), content: form.content.trim(), audience: form.audience, status: form.status, pinned: form.pinned, visibleFrom: form.visibleFrom || null, expiresAt: form.expiresAt || null, revision: form.revision }
    if (editingId.value) await learningApi.updateNotice(editingId.value, body); else await learningApi.createNotice(body)
    ElMessage.success(body.status === 'PUBLISHED' ? '公告已发布，按设置的时间和角色展示' : '公告草稿已保存')
    dialog.value = false; await load()
  } catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 确认删除草稿后，带上页面拿到的版本发出请求。
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除从未发布的草稿“${row.title}”？`, '删除草稿', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await learningApi.removeNotice(row.id, row.revision); ElMessage.success('草稿已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load()
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
}
// 按公告编号获取详情，后端会再次判断这条公告是否对当前账号可见。
async function openDetail(row) { try { detail.value = await learningApi.notice(row.id) } catch (exception) { ElMessage.error(exception.message) } }
// 用详情中的版本号标为已读，再刷新公告列表。
async function markRead() {
  saving.value = true
  try { await learningApi.readNotice(detail.value.id, detail.value.revision); detail.value = null; ElMessage.success('已标记为已读'); await load() }
  catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 根据状态、发布时间和展示时间推导后台列表上的状态文案。
function availability(row) { const now = new Date(); if (row.status === 'DRAFT') return row.publishedAt ? '已撤回' : '草稿'; if (row.expiresAt && new Date(row.expiresAt) <= now) return '已过期'; if (row.visibleFrom && new Date(row.visibleFrom) > now) return '待展示'; return '展示中' }
</script>

<!-- 列表按读者或管理身份显示操作，弹窗承载公告详情和编辑表单。 -->
<template><div><div class="page-heading"><div><span class="eyebrow">校园 / NOTICES</span><h1>{{ admin ? '公告管理' : '公告通知' }}</h1><p>{{ admin ? '按角色发布公告，可设置展示时间、截止时间和置顶。' : '查看面向当前角色的有效公告，更新后的公告将重新提示未读。' }}</p></div><el-button v-if="admin" type="primary" @click="openEditor()">新建公告</el-button></div><section class="panel"><div class="table-toolbar"><div class="search-actions wrap"><el-input v-model="keyword" clearable placeholder="搜索标题或正文" @keyup.enter="search" @clear="search" /><el-switch v-if="!admin" v-model="unread" active-text="只看未读" @change="search" /><el-button @click="search">查询</el-button></div><el-button :loading="loading" @click="load">刷新</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
<el-table v-if="admin" v-loading="loading" :data="rows" empty-text="暂无公告，可先保存草稿" class="course-table"><el-table-column label="标题" min-width="240"><template #default="{ row }"><el-tag v-if="row.pinned" size="small">置顶</el-tag> {{ row.title }}<div class="cell-note">版本 {{ row.revision }} · {{ row.authorName }}</div></template></el-table-column><el-table-column label="接收人" width="110"><template #default="{ row }">{{ audiences[row.audience] }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="availability(row) === '展示中' ? 'success' : 'info'">{{ availability(row) }}</el-tag></template></el-table-column><el-table-column label="展示时间" min-width="180"><template #default="{ row }">{{ row.visibleFrom ? dateTime(row.visibleFrom) : '发布后立即' }}<div class="cell-note">截止 {{ row.expiresAt ? dateTime(row.expiresAt) : '不限' }}</div></template></el-table-column><el-table-column label="操作" width="160"><template #default="{ row }"><el-button text type="primary" @click="openEditor(row)">编辑 / 撤回</el-button><el-button v-if="!row.publishedAt && row.status === 'DRAFT'" text type="danger" @click="remove(row)">删除</el-button></template></el-table-column></el-table>
<div v-else v-loading="loading" class="notice-list"><el-empty v-if="!rows.length" :description="unread ? '暂无未读公告' : '暂无有效公告'" /><button v-for="row in rows" :key="row.id" class="notice-card" @click="openDetail(row)"><div class="notice-title"><el-tag v-if="row.pinned" size="small">置顶</el-tag><span v-if="!row.readAt" class="unread-dot" /><h2>{{ row.title }}</h2><el-tag :type="row.readAt ? 'info' : 'warning'" size="small">{{ row.readAt ? '已读' : '未读' }}</el-tag></div><p>{{ row.content.slice(0, 160) }}{{ row.content.length > 160 ? '…' : '' }}</p><div class="cell-note">{{ row.authorName }} · {{ dateTime(row.publishedAt) }} · {{ audiences[row.audience] }}</div></button></div>
<div class="pagination-row"><span class="muted">共 {{ total }} 条{{ unread ? '未读' : '' }}公告</span><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="load" @size-change="search" /></div></section>
<el-dialog v-model="dialog" :title="editingId ? '编辑公告' : '新建公告'" width="760px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @opened="formRef?.clearValidate()"><el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="form-grid"><el-form-item label="标题" prop="title" class="full-width"><el-input v-model="form.title" maxlength="150" show-word-limit /></el-form-item><el-form-item label="正文（纯文本）" prop="content" class="full-width"><el-input v-model="form.content" type="textarea" :rows="8" maxlength="5000" show-word-limit /></el-form-item><el-form-item label="接收角色"><el-select v-model="form.audience"><el-option v-for="(label, value) in audiences" :key="value" :label="label" :value="value" /></el-select></el-form-item><el-form-item label="状态"><el-select v-model="form.status"><el-option label="草稿 / 撤回" value="DRAFT" /><el-option label="发布" value="PUBLISHED" /></el-select></el-form-item><el-form-item label="展示开始（留空即刻）"><el-date-picker v-model="form.visibleFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="发布后立即展示" /></el-form-item><el-form-item label="展示截止（留空不限）"><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="不限" /></el-form-item><el-form-item label="置顶"><el-switch v-model="form.pinned" active-text="在公告列表置顶" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ form.status === 'PUBLISHED' ? '保存并发布' : '保存草稿' }}</el-button></template></el-dialog>
<el-dialog :model-value="!!detail" title="公告详情" width="760px" :close-on-click-modal="false" @close="detail = null"><template v-if="detail"><h2>{{ detail.title }}</h2><p class="muted">{{ detail.authorName }} · {{ dateTime(detail.publishedAt) }}</p><div class="notice-body">{{ detail.content }}</div></template><template #footer><el-button :disabled="saving" @click="detail = null">关闭</el-button><el-button v-if="detail && !detail.readAt" type="primary" :loading="saving" @click="markRead">确认已读</el-button></template></el-dialog></div></template>
