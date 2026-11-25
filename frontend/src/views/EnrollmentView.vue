<script setup>
// 根据当前页面展示个人选课或后台选课记录，支持对应的查询和操作。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { academicApi, adminApi } from '../api/modules'
import { authState } from '../stores/auth'
import { dateTime, enrollmentStatuses, flattenEnrollment, scheduleText } from '../utils/academic'
import { downloadCsv } from '../utils/export'

const isAdmin = computed(() => authState.user?.role === 'ADMIN')
const semesters = ref([])
const semesterId = ref()
const keyword = ref('')
const status = ref()
const page = ref(1)
const size = ref(10)
const total = ref(0)
const rows = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(null)
// 管理员列表已由后端分页和筛选，学生个人记录按当前条件在页面中过滤。
const displayed = computed(() => isAdmin.value ? rows.value : rows.value.filter((row) => (!status.value || row.status === status.value)
  && `${row.courseName} ${row.classCode} ${row.teacherName}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const selected = computed(() => rows.value.filter((row) => row.status === 'ENROLLED'))
// 只累计状态为已选的课程学分。
const credits = computed(() => selected.value.reduce((sum, row) => sum + Number(row.credits), 0).toFixed(1))
const currentSemester = computed(() => semesters.value.find((item) => item.id === semesterId.value))
// 根据当前角色、选课状态和时间窗口决定是否显示可用的退课操作。
function canDrop(row) {
  // 按钮根据课程所属学期的选退课时间决定是否开放。后端收到请求后还会再次检查时间与成绩状态。
  const period = semesters.value.find((item) => item.id === row.semesterId)
  return period && new Date() >= new Date(period.selectionStart) && new Date() <= new Date(period.selectionEnd)
}
// 管理员查询后台分页记录，学生查询本人选退课记录。
async function load() {
  loading.value = true
  error.value = ''
  try {
    if (isAdmin.value) {
      const result = await adminApi.list('enrollments', { page: page.value, size: size.value, semesterId: semesterId.value, keyword: keyword.value.trim() || undefined, status: status.value })
      rows.value = result.records
      total.value = result.total
    } else {
      // 个人接口返回选课摘要和排课两部分。合并后表格可以直接读取课程名、状态和时段。
      rows.value = (await academicApi.enrollments(semesterId.value)).map(flattenEnrollment)
    }
  } catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 条件变化后从第一页开始查询。
function search() { page.value = 1; load() }
// 征得确认后退选本人课程，并重查列表与有效学分。
async function drop(row) {
  try {
    await ElMessageBox.confirm(`确定退选“${row.courseName}”吗？退课后名额将释放。`, '确认退课', { type: 'warning', confirmButtonText: '确认退课', cancelButtonText: '保留课程' })
    submitting.value = row.id
    await academicApi.drop(row.id)
    ElMessage.success('退课成功，历史记录已保留')
    await load()
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
  finally { submitting.value = null }
}
// 进入页面时先拿到学期列表，再读取对应的选课记录。
onMounted(async () => {
  try {
    semesters.value = await academicApi.semesters()
    if (!isAdmin.value) semesterId.value = semesters.value[0]?.id
    await load()
  } catch (exception) { error.value = exception.message }
})
</script>

<!-- 学生查看个人选课和退课操作，管理员查看可分页的记录表。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">选课 / ENROLLMENTS</span><h1>{{ isAdmin ? '选课记录' : '我的选课' }}</h1><p>{{ isAdmin ? '按学生、课程、学期与状态查询选退课记录。' : '查看课程记录及最近选退课时间。' }}</p></div><router-link v-if="!isAdmin" to="/courses"><el-button type="primary">继续选课</el-button></router-link></div>
    <div v-if="!isAdmin" class="selection-summary section-gap"><strong>{{ selected.length }}<small>门已选课程</small></strong><strong>{{ credits }}<small>已选学分{{ currentSemester ? ` / 上限 ${currentSemester.maxCredits}` : '' }}</small></strong><p v-if="currentSemester">选退课时间：{{ dateTime(currentSemester.selectionStart) }} 至 {{ dateTime(currentSemester.selectionEnd) }}</p></div>
    <section class="panel">
      <div class="table-toolbar"><div class="search-actions wrap"><el-input v-model="keyword" :placeholder="isAdmin ? '学号、姓名、课程或教学班' : '课程或教学班搜索'" clearable @keyup.enter="search" @clear="search" /><el-select v-model="semesterId" placeholder="全部学期" clearable @change="search"><el-option v-for="semester in semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select><el-select v-model="status" placeholder="全部状态" clearable @change="search"><el-option v-for="(label, value) in enrollmentStatuses" :key="value" :label="label" :value="value" /></el-select><el-button @click="search">查询</el-button></div><el-button :loading="loading" @click="load">刷新</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
      <div class="grade-actions"><span class="muted">按当前学期、关键词和状态导出全部匹配记录。</span><el-button @click="downloadCsv(isAdmin ? 'admin' : 'student', 'enrollments', { semesterId, keyword: keyword.trim() || undefined, status: status || undefined }, isAdmin ? '选课记录' : '我的选课')">导出 CSV</el-button></div>
      <el-table v-loading="loading" :data="displayed" empty-text="暂无符合条件的选课记录" class="course-table">
        <el-table-column v-if="isAdmin" label="学生" min-width="150"><template #default="{ row }">{{ row.studentName }}<div class="cell-note">{{ row.studentNo }}</div></template></el-table-column>
        <el-table-column label="课程 / 教学班" min-width="200"><template #default="{ row }"><strong>{{ row.courseName }}</strong><div class="cell-note">{{ row.classCode }}</div></template></el-table-column>
        <el-table-column prop="teacherName" label="教师" min-width="110" /><el-table-column prop="credits" label="学分" width="80" />
        <el-table-column prop="semesterName" label="学期" min-width="200" />
        <el-table-column v-if="!isAdmin" label="上课安排" min-width="280"><template #default="{ row }"><div v-for="slot in row.schedules" :key="slot.id" class="cell-note">{{ scheduleText(slot) }}</div></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ENROLLED' ? 'success' : 'info'">{{ enrollmentStatuses[row.status] }}</el-tag></template></el-table-column>
        <el-table-column label="选课时间" min-width="170"><template #default="{ row }">{{ dateTime(row.selectedAt) }}</template></el-table-column>
        <el-table-column label="退课时间" min-width="170"><template #default="{ row }">{{ dateTime(row.droppedAt) }}</template></el-table-column>
        <el-table-column v-if="!isAdmin" label="操作" width="130" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ENROLLED'" text type="danger" :disabled="!canDrop(row) || submitting !== null" :loading="submitting === row.id" @click="drop(row)">{{ canDrop(row) ? '退选课程' : '不在退课时间' }}</el-button><span v-else class="muted">已退选</span></template></el-table-column>
      </el-table>
      <div v-if="isAdmin" class="pagination-row"><span class="muted">共 {{ total }} 条记录</span><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="load" @size-change="search" /></div>
    </section>
  </div>
</template>
