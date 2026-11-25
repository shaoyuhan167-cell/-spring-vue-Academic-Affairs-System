<script setup>
// 用同一套列表和表单管理学生、教师、课程和学期，字段由当前资源决定。
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/modules'
import { resources } from '../utils/management'
import { dateTime } from '../utils/academic'
import { downloadCsv } from '../utils/export'

const route = useRoute()
// 从路由元信息确定当前要管理学生、教师、课程还是学期。
const resource = computed(() => route.meta.resource)
const definition = computed(() => resources[resource.value])
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const editing = ref(null)
const dialogOpen = ref(false)
const editorClosing = ref(false)
const saving = ref(false)
const form = reactive({})
const formRef = ref()
let requestVersion = 0
// 编辑现有数据时隐藏只需在新建时填写的字段。
// 新建记录和编辑记录复用同一个表单。标记为 createOnly 的字段只在新建时出现，也只会在新建时被提交。
const visibleFields = computed(() => definition.value.fields.filter((field) => !editing.value || !field.createOnly))
// 根据当前显示的字段动态生成必填校验规则。
const rules = computed(() => Object.fromEntries(visibleFields.value.filter((field) => field.required).map((field) => [field.key, [{ required: true, message: `请填写${field.label}`, trigger: 'blur' }]])))
const passwordDialog = ref(false)
const passwordTarget = ref(null)
const newPassword = ref('')

// 按当前资源和筛选条件读取列表。请求编号用来丢弃过期响应。
async function load() {
  // 资源切换和快速翻页都可能让请求交错。只允许最后一次查询写入表格。
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.list(resource.value, { page: page.value, size: size.value, keyword: keyword.value.trim() || undefined })
    if (version !== requestVersion) return
    rows.value = result.records
    total.value = result.total
  } catch (exception) { if (version === requestVersion) error.value = exception.message }
  finally { if (version === requestVersion) loading.value = false }
}
// 共用页面切换资源时重置旧列表和查询条件，再加载新资源。
watch(resource, () => { rows.value = []; keyword.value = ''; page.value = 1; dialogOpen.value = false; load() }, { immediate: true })
// 用状态标记关闭动画，避免弹窗关闭期间被再次打开。
watch(dialogOpen, (open, previous) => { if (previous && !open) editorClosing.value = true })
// 筛选时回到第一页再加载。
function search() { page.value = 1; load() }
// 用当前记录或字段默认值填表，并清掉上次打开时的校验提示。
async function openEditor(row = null) {
  if (editorClosing.value) return
  editing.value = row
  // 共用表单在换资源或档案前清除原属性，再按新资源定义逐项填入，避免把上次表单的数据一起发给后端。
  for (const key of Object.keys(form)) delete form[key]
  for (const field of definition.value.fields) form[field.key] = row?.[field.key] ?? field.default ?? ''
  dialogOpen.value = true
  await nextTick()
  formRef.value?.clearValidate()
}
// 按配置提取接口接收的字段，编辑时略去只在新建时使用的字段。
function payload(row) {
  return Object.fromEntries(definition.value.fields.filter((field) => !editing.value || !field.createOnly).map((field) => [field.key, row[field.key]]))
}
// 校验动态表单，然后按新增或编辑状态调用对应接口。
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    if (editing.value) await adminApi.update(resource.value, editing.value.id, payload(form))
    else await adminApi.create(resource.value, payload(form))
    ElMessage.success('保存成功')
    dialogOpen.value = false
    await load()
  } catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 确认后切换学生或教师账号的启用状态。
async function toggle(row) {
  try {
    await ElMessageBox.confirm(`确定${row.enabled ? '停用' : '启用'}“${row.displayName}”的账号吗？`, '账号状态', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
    const data = Object.fromEntries(definition.value.fields.filter((field) => !field.createOnly).map((field) => [field.key, row[field.key]]))
    await adminApi.update(resource.value, row.id, { ...data, enabled: !row.enabled })
    ElMessage.success('账号状态已更新')
    await load()
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
}
// 确认后删除当前资源记录。遇到后台拒绝时在页面显示原因。
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除“${row.name}”吗？已有教学引用的数据不能删除。`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await adminApi.remove(resource.value, row.id)
    ElMessage.success('已删除')
    if (rows.value.length === 1 && page.value > 1) page.value--
    await load()
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
}
// 选中要重置密码的账号，并清空上次输入。
function openPassword(row) { passwordTarget.value = row; newPassword.value = ''; passwordDialog.value = true }
// 提交新密码给后台，成功后关闭账号密码弹窗。
async function resetPassword() {
  if (newPassword.value.length < 10) return ElMessage.warning('密码至少 10 个字符，且同时包含字母和数字')
  saving.value = true
  try {
    await adminApi.resetPassword(resource.value, passwordTarget.value.id, newPassword.value)
    ElMessage.success('密码已重置，原有登录已失效')
    passwordDialog.value = false
  } catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
</script>

<!-- 按管理资源的配置渲染表格和表单，并显示相应的账号操作。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">教学管理 / MANAGEMENT</span><h1>{{ definition.title }}</h1><p>{{ definition.description }}</p></div><el-button type="primary" :disabled="editorClosing" @click="openEditor()">新增{{ definition.noun }}</el-button></div>
    <section class="panel">
      <div class="table-toolbar"><div class="search-actions"><el-input v-model="keyword" placeholder="输入编号或名称搜索" clearable @keyup.enter="search" @clear="search" /><el-button @click="search">查询</el-button></div><div class="search-actions"><el-button @click="downloadCsv('admin', resource, { keyword: keyword.trim() || undefined }, definition.title)">导出 CSV</el-button><el-button :loading="loading" @click="load">刷新</el-button></div></div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="section-gap" />
      <el-table v-loading="loading" :data="rows" empty-text="暂无数据，请点击右上方新增" class="course-table">
        <el-table-column v-for="column in definition.columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.width || 130" show-overflow-tooltip><template #default="{ row }">{{ column.type === 'datetime' ? dateTime(row[column.key]) : (row[column.key] ?? '—') }}</template></el-table-column>
        <el-table-column v-if="definition.person" label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" :width="definition.person ? 245 : 150" fixed="right"><template #default="{ row }"><el-button text type="primary" :disabled="editorClosing" @click="openEditor(row)">编辑</el-button><template v-if="definition.person"><el-button text @click="openPassword(row)">重置密码</el-button><el-button text :type="row.enabled ? 'warning' : 'success'" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template><el-button v-else text type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-row"><span class="muted">共 {{ total }} 条记录</span><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="load" @size-change="search" /></div>
    </section>
    <el-dialog v-model="dialogOpen" :title="`${editing ? '编辑' : '新增'}${definition.noun}`" width="600px" :close-on-click-modal="false" :before-close="(done) => !saving && done()" destroy-on-close @closed="editorClosing = false">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="save">
        <div class="form-grid"><el-form-item v-for="field in visibleFields" :key="field.key" :label="field.label" :prop="field.key" :class="{ 'full-width': field.type === 'textarea' }">
          <el-switch v-if="field.type === 'switch'" v-model="form[field.key]" active-text="启用" inactive-text="停用" />
          <el-input-number v-else-if="field.type === 'number'" v-model="form[field.key]" :min="field.min" :max="field.max" :step="field.step || 1" :precision="field.precision || 0" controls-position="right" />
          <el-date-picker v-else-if="['date','datetime'].includes(field.type)" v-model="form[field.key]" :type="field.type" :value-format="field.type === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'" :placeholder="`请选择${field.label}`" />
          <el-input v-else v-model="form[field.key]" :type="field.type === 'textarea' ? 'textarea' : field.type === 'password' ? 'password' : 'text'" :rows="3" :maxlength="field.max" :disabled="!!editing && field.immutable" :show-password="field.type === 'password'" :placeholder="field.type === 'password' ? '至少10位，包含字母和数字' : `请输入${field.label}`" :autocomplete="field.type === 'password' ? 'new-password' : 'off'" />
        </el-form-item></div>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="passwordDialog" title="重置账号密码" width="430px" :close-on-click-modal="false"><p>为 {{ passwordTarget?.displayName }} 设置新密码，已有登录将失效。</p><el-input v-model="newPassword" type="password" show-password placeholder="至少10位，包含字母和数字" autocomplete="new-password" /><template #footer><el-button @click="passwordDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="resetPassword">确认重置</el-button></template></el-dialog>
  </div>
</template>
