<script setup>
// 展示课程目录和剩余名额，支持单选和暂存多门课程后一起提交。
import { computed, onMounted, ref } from 'vue'
import { academicApi, courseApi } from '../api/modules'
import { authState } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { flattenEnrollment, scheduleOverlapText, scheduleText } from '../utils/academic'

const courses = ref([])
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const semesters = ref([])
const semesterId = ref()
const selected = ref([])
const submitting = ref(null)
const batchSubmitting = ref(false)
const pending = ref([])
const batchError = ref('')
const unavailablePendingIds = ref(new Set())
let catalogRequest = 0
let initialized = false
const maxBatchSize = 20
const isStudent = computed(() => authState.user?.role === 'STUDENT')
// 目录刷新、单门提交和批量提交期间共用忙碌状态，避免页面重复发出选课操作。
const busy = computed(() => loading.value || submitting.value !== null || batchSubmitting.value)
// 只把仍有效的选课算进已选列表，退课历史用于选课记录页。
const active = computed(() => selected.value.filter((item) => item.info.status === 'ENROLLED').map(flattenEnrollment))
const selectedIds = computed(() => new Set(active.value.map((item) => item.teachingClassId)))
// 按教学班编号查找目录中的最新信息。
const catalogById = computed(() => new Map(courses.value.map((item) => [item.id, item])))
// 缓存已勾选的教学班编号，用来判断是否重复加入。
const pendingIds = computed(() => new Set(pending.value.map((item) => item.id)))
// 待选列表优先用目录刚刷新的数据，缺少该班时保留原条目。
const pendingCourses = computed(() => pending.value.map((item) => catalogById.value.get(item.id) ?? item))
// 按课程编号、课程名、教师或班级编号在当前目录中搜索。
const filteredCourses = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return courses.value.filter((item) =>
    [item.courseCode, item.courseName, item.teacherName, item.classCode].some((value) => value.toLowerCase().includes(search)))
})

// 先判断已选、选课窗口与名额，给不可选的教学班标出原因。
function unavailableReason(row) {
  if (selectedIds.value.has(row.id)) return '已选'
  if (!row.selectionOpen) return '未到选课时间'
  if (row.selectedCount >= row.capacity) return '已满'
  return ''
}

const courseLabel = (row) => `“${row.courseName}”（${row.classCode}）`

// 在同一学期内比较两门课，检查同课重复与实际上课时间重叠。
function choiceIssue(row, other, source) {
  if (row.semesterId !== other.semesterId) return null
  const pair = `${courseLabel(row)}与${source}${courseLabel(other)}`
  if (row.courseId === other.courseId) return `${pair}属于同一门课，请选择一个教学班。`
  const overlap = scheduleOverlapText(row.schedules, other.schedules)
  return overlap ? `${pair}时间冲突（${overlap}）。` : null
}

// 待选课程分别和已选课、以及前面加入待选的课程比较，每对待选课程只检查一次。
const pendingIssues = computed(() => {
  const issues = []
  pendingCourses.value.forEach((row, index) => {
    const reason = unavailablePendingIds.value.has(row.id) ? '已从课程目录移除' : unavailableReason(row)
    if (reason) {
      issues.push(`${courseLabel(row)}${reason}，请移出待选。`)
      return
    }
    // 先和数据库已有的有效选课比较，再和本次批量清单中前面的条目比较，便于给出两种不同的冲突来源。
    for (const other of active.value) {
      const issue = choiceIssue(row, other, '已选课程')
      if (issue) issues.push(issue)
    }
    for (const other of pendingCourses.value.slice(0, index)) {
      const issue = choiceIssue(row, other, '待选课程')
      if (issue) issues.push(issue)
    }
  })
  return issues
})

// 勾选只修改页面上的待选清单，取消勾选则移出该项。
function updatePending(row, checked) {
  batchError.value = ''
  if (!checked) {
    pending.value = pending.value.filter((item) => item.id !== row.id)
  } else if (!pendingIds.value.has(row.id) && pending.value.length < maxBatchSize && !unavailableReason(row)) {
    pending.value.push(row)
  }
}

// 按学期加载最新目录，需要时也同步学期列表和个人选课记录。
async function reloadCatalog(refreshContext = false) {
  // 用递增编号识别最新请求，避免快速切换学期时旧响应覆盖当前目录。
  const request = ++catalogRequest
  loading.value = true
  error.value = ''
  try {
    if (refreshContext) {
      const [periods, mine] = await Promise.all([academicApi.semesters(), isStudent.value ? academicApi.enrollments() : Promise.resolve([])])
      if (request !== catalogRequest) return
      semesters.value = periods
      selected.value = mine
      if (!initialized) semesterId.value = periods[0]?.id
      initialized = true
    }
    const scope = semesterId.value || undefined
    const catalog = await courseApi.list(scope)
    if (request !== catalogRequest) return
    courses.value = catalog
    const byId = catalogById.value
    const unavailable = new Set(unavailablePendingIds.value)
    pending.value = pending.value.map((row) => {
      // 当前目录只反映所选学期，其他学期的待选项继续保留。
      if (scope && row.semesterId !== scope) return row
      // 同一学期的待选项用最新目录更新。查不到的项目仍保留在清单中，标为不可选并提示用户移除。
      if (byId.has(row.id)) {
        unavailable.delete(row.id)
        return byId.get(row.id)
      }
      unavailable.add(row.id)
      return row
    })
    unavailablePendingIds.value = unavailable
  } catch (exception) {
    if (request === catalogRequest) {
      courses.value = []
      error.value = exception.message
    }
  } finally {
    if (request === catalogRequest) loading.value = false
  }
}
const load = () => reloadCatalog(true)
// 首次打开目录时加载学期、目录和用于冲突预判的个人选课记录。
onMounted(load)

// 确认单选请求，成功后更新待选清单并刷新目录。
async function enroll(row) {
  try {
    await ElMessageBox.confirm(`选择“${row.courseName}”（${row.credits} 学分）？请确认上课时间安排。`, '确认选课', { confirmButtonText: '确认选课', cancelButtonText: '取消' })
    submitting.value = row.id
    await academicApi.enroll(row.id)
    updatePending(row, false)
    ElMessage.success('选课成功，可在“我的选课”和“我的课表”中查看')
    await load()
  } catch (exception) {
    if (exception instanceof Error) { ElMessage.error(exception.message); await load() }
  } finally { submitting.value = null }
}

// 提交失败时保留待选项，刷新目录后用户可以根据提示调整。
async function submitPending() {
  if (busy.value || !pending.value.length || pendingIssues.value.length) return
  batchSubmitting.value = true
  batchError.value = ''
  try {
    // 提交时只带教学班编号。页面上的冲突和容量提示是预判，服务器会按最新数据库状态重新核对整组。
    const result = await academicApi.enrollBatch(pending.value.map((row) => row.id))
    pending.value = []
    ElMessage.success(`已选 ${result.length} 门课程，可在“我的课表”中查看`)
  } catch (exception) {
    // 服务器拒绝批次时保留原选择并显示原因，便于修正后重试。
    batchError.value = exception.message
  } finally {
    await load()
    batchSubmitting.value = false
  }
}
</script>

<!-- 学生可以在待选面板集中处理批量选择，目录表格也保留单门选课入口。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">课程 / CATALOG</span><h1>发现你的下一门课</h1><p>按学期选择课程，查看上课时间、教师与剩余名额。</p></div><router-link v-if="isStudent" to="/my-courses"><el-button>我的选课</el-button></router-link></div>
    <section v-if="isStudent" class="panel section-gap" aria-label="批量选课">
      <div class="panel-heading selection-heading">
        <div>
          <h2>批量选课</h2>
          <p>勾选课程目录中的「待选」，确认时间安排后一起提交。每次最多选 {{ maxBatchSize }} 门。</p>
        </div>
        <el-button type="primary" :disabled="busy || !pending.length || !!pendingIssues.length" :loading="batchSubmitting" @click="submitPending">
          确认选课（{{ pending.length }}）
        </el-button>
      </div>
      <ul v-if="pendingCourses.length" class="pending-courses" aria-label="待选课程">
        <li v-for="row in pendingCourses" :key="row.id">
          <span>{{ row.courseName }} · {{ row.classCode }}</span>
          <el-button text type="danger" size="small" :disabled="busy" :aria-label="`移除${courseLabel(row)}`" @click="updatePending(row, false)">移除</el-button>
        </li>
      </ul>
      <el-alert v-if="pendingIssues.length" title="请调整待选课程" type="warning" :closable="false" show-icon>
        <ul class="pending-issues">
          <li v-for="issue in pendingIssues" :key="issue">{{ issue }}</li>
        </ul>
        <p>可移除冲突的待选课程，或改选时间合适的教学班。</p>
      </el-alert>
      <el-alert v-if="batchError" :title="batchError" type="error" :closable="false" show-icon />
    </section>
    <section class="panel">
      <div class="table-toolbar"><div class="search-actions"><el-input v-model="keyword" placeholder="搜索课程名称、编号或教师" clearable aria-label="搜索课程" class="course-search" /><el-select v-model="semesterId" placeholder="选择学期" clearable @change="reloadCatalog()"><el-option v-for="semester in semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select></div><div class="toolbar-right"><span class="muted">共 {{ filteredCourses.length }} 个教学班</span><el-button :loading="loading" :disabled="busy" @click="load">刷新</el-button></div></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="section-gap" />
      <el-table v-loading="loading" :data="filteredCourses" empty-text="暂无符合条件的已发布课程" class="course-table">
        <el-table-column v-if="isStudent" label="待选" width="64" align="center" fixed="left">
          <template #default="{ row }">
            <input type="checkbox" class="pending-checkbox" :aria-label="`待选${courseLabel(row)}`"
              :checked="pendingIds.has(row.id)" :disabled="busy || (!pendingIds.has(row.id) && (!!unavailableReason(row) || pending.length >= maxBatchSize))"
              @change="updatePending(row, $event.target.checked)">
          </template>
        </el-table-column>
        <el-table-column label="课程信息" min-width="220"><template #default="{ row }"><div class="course-name">{{ row.courseName }}</div><small class="muted">{{ row.courseCode }} · {{ row.classCode }}</small></template></el-table-column>
        <el-table-column prop="teacherName" label="任课教师" min-width="110" />
        <el-table-column prop="credits" label="学分" width="80" />
        <el-table-column prop="hours" label="学时" width="80" />
        <el-table-column prop="semesterName" label="开课学期" min-width="220" />
        <el-table-column label="上课安排" min-width="280"><template #default="{ row }"><div v-for="slot in row.schedules" :key="slot.id" class="cell-note">{{ scheduleText(slot) }}</div></template></el-table-column>
        <el-table-column label="已选 / 容量" min-width="120"><template #default="{ row }"><span class="capacity-label">{{ row.selectedCount }} / {{ row.capacity }}</span></template></el-table-column>
        <el-table-column v-if="isStudent" label="选课" width="135" fixed="right"><template #default="{ row }"><el-button type="primary" :plain="selectedIds.has(row.id)" :disabled="busy || !!unavailableReason(row)" :loading="submitting === row.id" @click="enroll(row)">{{ unavailableReason(row) || '选择课程' }}</el-button></template></el-table-column>
      </el-table>
    </section>
  </div>
</template>

<!-- 局部样式控制待选面板、状态提示和选课按钮的布局。 -->
<style scoped>
.selection-heading { flex-wrap: wrap; margin-bottom: 0; }
.pending-courses { display: flex; flex-wrap: wrap; gap: 8px; padding: 0; margin: 16px 0; list-style: none; }
.pending-courses li { display: flex; align-items: center; gap: 6px; padding: 2px 8px 2px 12px; border: 1px solid #dbe4d8; border-radius: 8px; font-size: 13px; }
.pending-issues { padding-left: 18px; margin: 4px 0; }
.pending-checkbox { width: 16px; height: 16px; accent-color: var(--el-color-primary); vertical-align: middle; }
</style>
