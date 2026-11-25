<script setup>
// 维护教学班的教师、容量和排课，也可以查看班上的选课名单。
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { academicApi, adminApi } from '../api/modules'
import { classStatuses, scheduleText, weekdays } from '../utils/academic'
import { downloadCsv } from '../utils/export'

const rows = ref([])
const options = ref({ courses: [], teachers: [], semesters: [] })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const keyword = ref('')
const semesterId = ref()
const page = ref(1)
const size = ref(10)
const total = ref(0)
const dialogOpen = ref(false)
const editorClosing = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({})
const rosterOpen = ref(false)
const rosterRows = ref([])
const rosterLoading = ref(false)
const rosterTitle = ref('')
const required = (label) => [{ required: true, message: `请填写${label}`, trigger: 'change' }]
const rules = { classCode: required('教学班编号'), courseId: required('课程'), teacherId: required('教师'), semesterId: required('学期'), capacity: required('容量'), status: required('状态') }

// 从后端加载当前筛选条件与页码对应的教学班。
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await adminApi.list('classes', { page: page.value, size: size.value, keyword: keyword.value.trim() || undefined, semesterId: semesterId.value })
    rows.value = result.records
    total.value = result.total
  } catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 筛选条件变化后从第一页重新查询。
function search() { page.value = 1; load() }
// 打开表单前刷新下拉选项。编辑时复制排课数据，避免直接改列表。
async function openEditor(row = null) {
  if (editorClosing.value) return
  try { options.value = await adminApi.options() }
  catch (exception) { return ElMessage.error(exception.message) }
  editing.value = row
  // 编辑时为每段排课复制一份，未保存或取消时列表原数据不会随输入框变化。
  Object.assign(form, row ? { ...row, schedules: row.schedules.map((slot) => ({ ...slot })) }
    : { classCode: '', courseId: undefined, teacherId: undefined, semesterId: semesterId.value || options.value.semesters[0]?.id, capacity: 60, status: 'DRAFT', schedules: [] })
  dialogOpen.value = true
}
// 关闭动画尚未结束时暂时禁止再次打开编辑器。
watch(dialogOpen, (open, previous) => { if (previous && !open) editorClosing.value = true })
// 在表单中新增一个可编辑的默认上课时段。
function addSchedule() { form.schedules.push({ weekday: 1, startPeriod: 1, endPeriod: 2, startWeek: 1, endWeek: 16, classroom: '' }) }
// 挑出后端需要的班级和排课字段，新建、编辑和改状态共用这份结构。
function payload(row, status = row.status) {
  return { classCode: row.classCode, courseId: row.courseId, teacherId: row.teacherId, semesterId: row.semesterId, capacity: row.capacity, status,
    schedules: row.schedules.map(({ weekday, startPeriod, endPeriod, startWeek, endWeek, classroom }) => ({ weekday, startPeriod, endPeriod, startWeek, endWeek, classroom })) }
}
// 校验表单后按编辑状态调用新增或更新接口。
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    if (editing.value) await adminApi.update('classes', editing.value.id, payload(form))
    else await adminApi.create('classes', payload(form))
    dialogOpen.value = false
    ElMessage.success('教学班已保存')
    await load()
  } catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 已发布的班级可以关闭选课，其他状态可以尝试发布。
async function changeStatus(row) {
  // 发布和关闭沿用教学班更新接口。保留其他字段，只替换状态，后端仍会检查原排课与历史限制。
  const status = row.status === 'PUBLISHED' ? 'CLOSED' : 'PUBLISHED'
  try {
    await adminApi.update('classes', row.id, payload(row, status))
    ElMessage.success(status === 'PUBLISHED' ? '教学班已发布' : '教学班已关闭选课')
    await load()
  } catch (exception) { ElMessage.error(exception.message) }
}
// 删除前要求确认。删完当前页最后一条时回到上一页。
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除教学班 ${row.classCode} 吗？存在选课历史时不能删除。`, '删除确认', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
    await adminApi.remove('classes', row.id)
    if (rows.value.length === 1 && page.value > 1) page.value--
    ElMessage.success('教学班已删除')
    await load()
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
}
// 打开名单弹窗并按教学班编号请求选课学生。
async function roster(row) {
  // 弹窗每次先置空，再显示当前班级的标题和加载状态，避免把上一个班级的学生误显示在此处。
  rosterRows.value = []
  rosterTitle.value = `${row.courseName} · 学生名单`
  rosterOpen.value = true
  rosterLoading.value = true
  try { rosterRows.value = await academicApi.roster(row.id) }
  catch (exception) { ElMessage.error(exception.message) }
  finally { rosterLoading.value = false }
}
// 首次打开页面时读取下拉选项和教学班列表。
onMounted(async () => { try { options.value = await adminApi.options() } catch (exception) { error.value = exception.message }; await load() })
</script>

<!-- 表格承担筛选和检索，弹窗分别用于维护排课和查看学生名单。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">教学安排 / CLASSES</span><h1>开课管理</h1><p>设置任课教师、名额与上课时间，发布后进入学生课程目录。</p></div><el-button type="primary" :disabled="editorClosing" @click="openEditor()">新增教学班</el-button></div>
    <section class="panel">
      <div class="table-toolbar"><div class="search-actions wrap"><el-input v-model="keyword" placeholder="课程、教师或教学班编号" clearable @keyup.enter="search" @clear="search" /><el-select v-model="semesterId" placeholder="全部学期" clearable @change="search"><el-option v-for="semester in options.semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select><el-button @click="search">查询</el-button></div><div class="search-actions"><el-button @click="downloadCsv('admin', 'classes', { keyword: keyword.trim() || undefined, semesterId }, '教学班')">导出 CSV</el-button><el-button :loading="loading" @click="load">刷新</el-button></div></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
      <el-table v-loading="loading" :data="rows" empty-text="暂无教学班" class="course-table">
        <el-table-column label="课程 / 教学班" min-width="200"><template #default="{ row }"><strong>{{ row.courseName }}</strong><div class="cell-note">{{ row.classCode }}</div></template></el-table-column>
        <el-table-column prop="teacherName" label="任课教师" min-width="110" />
        <el-table-column prop="semesterName" label="学期" min-width="200" />
        <el-table-column label="上课安排" min-width="270"><template #default="{ row }"><div v-for="slot in row.schedules" :key="slot.id" class="cell-note">{{ scheduleText(slot) }}</div><span v-if="!row.schedules.length" class="muted">尚未排课</span></template></el-table-column>
        <el-table-column label="人数" width="95"><template #default="{ row }">{{ row.selectedCount }} / {{ row.capacity }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ classStatuses[row.status] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="255" fixed="right"><template #default="{ row }"><el-button text type="primary" :disabled="editorClosing" @click="openEditor(row)">编辑</el-button><el-button text @click="changeStatus(row)">{{ row.status === 'PUBLISHED' ? '关闭' : '发布' }}</el-button><el-button text @click="roster(row)">名单</el-button><el-button text type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-row"><span class="muted">共 {{ total }} 个教学班</span><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="load" @size-change="search" /></div>
    </section>
    <el-dialog v-model="dialogOpen" :title="editing ? '编辑教学班' : '新增教学班'" width="900px" :close-on-click-modal="false" :before-close="(done) => !saving && done()" destroy-on-close @closed="editorClosing = false">
      <el-alert title="发布前必须排课，系统会检查教师与教室冲突。已有选课历史时，仅允许修改容量与状态。" type="info" :closable="false" class="section-gap" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid three-columns">
          <el-form-item label="教学班编号" prop="classCode"><el-input v-model="form.classCode" maxlength="40" placeholder="例如 CS001-2026-02" /></el-form-item>
          <el-form-item label="课程" prop="courseId"><el-select v-model="form.courseId" filterable placeholder="选择基础课程"><el-option v-for="course in options.courses" :key="course.id" :label="`${course.courseCode} · ${course.name}`" :value="course.id" /></el-select></el-form-item>
          <el-form-item label="教师" prop="teacherId"><el-select v-model="form.teacherId" filterable placeholder="选择已启用教师"><el-option v-for="teacher in options.teachers" :key="teacher.id" :label="`${teacher.displayName} · ${teacher.teacherNo}`" :value="teacher.id" /></el-select></el-form-item>
          <el-form-item label="学期" prop="semesterId"><el-select v-model="form.semesterId" placeholder="选择学期"><el-option v-for="semester in options.semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select></el-form-item>
          <el-form-item label="人数上限" prop="capacity"><el-input-number v-model="form.capacity" :min="1" :max="1000" /></el-form-item>
          <el-form-item label="开课状态" prop="status"><el-select v-model="form.status"><el-option v-for="(label, value) in classStatuses" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        </div>
        <div class="panel-heading"><h2>上课安排</h2><el-button :disabled="form.schedules?.length >= 12" @click="addSchedule">添加时间段</el-button></div>
        <div v-for="(slot, index) in form.schedules" :key="index" class="schedule-editor">
          <div class="schedule-row"><el-select v-model="slot.weekday" aria-label="星期"><el-option v-for="(day, dayIndex) in weekdays" :key="day" :label="day" :value="dayIndex + 1" /></el-select><label>第 <el-input-number v-model="slot.startPeriod" :min="1" :max="12" controls-position="right" /> 至 <el-input-number v-model="slot.endPeriod" :min="slot.startPeriod" :max="12" controls-position="right" /> 节</label><el-button text type="danger" @click="form.schedules.splice(index, 1)">移除</el-button></div>
          <div class="schedule-row"><label>第 <el-input-number v-model="slot.startWeek" :min="1" :max="30" controls-position="right" /> 至 <el-input-number v-model="slot.endWeek" :min="slot.startWeek" :max="30" controls-position="right" /> 周</label><el-input v-model="slot.classroom" maxlength="100" placeholder="上课地点，例如 A-301" /></div>
        </div>
        <el-empty v-if="!form.schedules?.length" description="尚未添加时间段，草稿可暂不排课" :image-size="65" />
      </el-form>
      <template #footer><el-button :disabled="saving" @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存教学班</el-button></template>
    </el-dialog>
    <el-dialog v-model="rosterOpen" :title="rosterTitle" width="760px"><el-table v-loading="rosterLoading" :data="rosterRows" empty-text="暂无有效选课学生"><el-table-column prop="studentNo" label="学号" /><el-table-column prop="displayName" label="姓名" /><el-table-column prop="major" label="专业" /><el-table-column prop="className" label="班级" /></el-table></el-dialog>
  </div>
</template>
