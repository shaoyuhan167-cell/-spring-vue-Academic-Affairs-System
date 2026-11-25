<script setup>
// 学生查看已发布成绩，教师和管理员使用成绩表录入、保存及发布成绩。
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { academicApi, learningApi } from '../api/modules'
import { authState } from '../stores/auth'
import { dateTime } from '../utils/academic'
import { downloadCsv } from '../utils/export'

const student = computed(() => authState.user?.role === 'STUDENT')
const semesters = ref([]), semesterId = ref(), classes = ref([]), classId = ref()
const summary = ref(null), book = ref(null), rows = ref([]), weight = ref(30)
const loading = ref(false), saving = ref(false), error = ref(''), dirty = ref(false)
const published = computed(() => (book.value?.publishedCount || 0) > 0)
const busy = computed(() => loading.value || saving.value)
let requestVersion = 0
// 把后端成绩表转成可编辑数据，并在同步成功后清除未保存标记。
function applyBook(value) { book.value = value; rows.value = value.rows.map(row => ({ ...row, usualScore: row.usualScore ?? undefined, examScore: row.examScore ?? undefined })); weight.value = value.usualWeight; dirty.value = false }
// 按教学班加载成绩，忽略切换班级之前的旧请求结果。
async function loadBook() {
  // 每次打开新成绩表先清掉旧表。只允许最新请求写入当前班级的分数、错误提示和加载状态。
  const version = ++requestVersion
  book.value = null; rows.value = []; error.value = ''
  if (!classId.value) return
  loading.value = true
  try { const result = await learningApi.book(classId.value); if (version === requestVersion) applyBook(result) }
  catch (exception) { if (version === requestVersion) error.value = exception.message }
  finally { if (version === requestVersion) loading.value = false }
}
// 学生加载本人成绩汇总，教师和管理员先加载有权访问的班级。
async function load() {
  loading.value = true; error.value = ''
  try {
    if (student.value) summary.value = await learningApi.myGrades(semesterId.value)
    else { classes.value = await learningApi.classes(semesterId.value); if (!classes.value.some(c => c.id === classId.value)) classId.value = classes.value[0]?.id; await loadBook() }
  } catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 提交班级权重、每行成绩和对应版本。空分数用 null 传给后端。
// 录入页拿到原成绩版本后随表单一同提交。后端用它判断成绩是否在本页打开之后被其他人改过。
function payload() { return { usualWeight: weight.value, entries: rows.value.map(row => ({ enrollmentId: row.enrollmentId, usualScore: row.usualScore ?? null, examScore: row.examScore ?? null, remark: row.remark, revision: row.revision })) } }
// 提交全班成绩草稿，用后端返回的分数和版本更新表格。
async function save() {
  saving.value = true
  try { applyBook(await learningApi.saveGrades(classId.value, payload())); ElMessage.success('全班成绩草稿已保存') }
  catch (exception) { ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 确认后发布或撤回全班成绩，并刷新当前班的成绩表。
async function changeStatus(action) {
  try {
    await ElMessageBox.confirm(action === 'publish' ? '发布后学生可查看成绩，本班将停止新增选课。确认发布全班成绩？' : '撤回后学生暂时无法查看本班成绩，教师可重新修改。确认撤回？', action === 'publish' ? '发布成绩' : '撤回成绩', { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' })
    saving.value = true
    // 发布或撤回以后使用服务器返回的新状态与版本覆盖编辑表，后续操作据此判断是否可编辑。
    applyBook(await learningApi.gradeStatus(classId.value, action, payload()))
    ElMessage.success(action === 'publish' ? '成绩已发布' : '成绩已撤回')
  } catch (exception) { if (exception instanceof Error) ElMessage.error(exception.message) }
  finally { saving.value = false }
}
// 有尚未保存的编辑时，先询问用户是否愿意放弃修改。
async function discardAllowed() {
  // 刷新和离开页面共用此判断。只有发生过未保存的编辑时才弹确认框。
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('存在未保存的成绩，是否放弃修改？', '未保存修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
// 仅在允许丢弃当前编辑时重新加载成绩数据。
async function refresh() { if (await discardAllowed()) { dirty.value = false; await load() } }
// 离开成绩页前复用未保存修改的确认逻辑。
onBeforeRouteLeave(discardAllowed)
// 表格用正在编辑的分数和权重预览总分，保存后的正式分数由后端计算。
function preview(row) { return row.usualScore == null || row.examScore == null ? '—' : ((Number(row.usualScore) * weight.value + Number(row.examScore) * (100 - weight.value)) / 100).toFixed(2) }
// 学生按学期导出本人记录，教师和管理员按班级导出成绩表。
function exportGrades() { downloadCsv(student.value ? 'student' : 'teaching', 'grades', student.value ? { semesterId: semesterId.value } : { classId: classId.value }, student.value ? '我的成绩' : `${book.value?.teachingClass.classCode}_成绩`) }
// 进入成绩页面时先设置初始学期，再按角色加载数据。
onMounted(async () => { try { semesters.value = await academicApi.semesters(); semesterId.value = semesters.value[0]?.id; await load() } catch (exception) { error.value = exception.message } })
</script>

<!-- 学生区显示发布后的成绩。教师和管理员区按当前成绩状态显示可用操作。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">学业 / GRADES</span><h1>{{ student ? '我的成绩' : '成绩管理' }}</h1><p>{{ student ? '查询已发布成绩与学分完成情况。' : '按教学班录入成绩，保存草稿后统一发布。' }}</p></div><el-select v-model="semesterId" clearable placeholder="全部学期" class="heading-select" :disabled="dirty || busy" @change="load"><el-option v-for="semester in semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
    <div v-if="student && summary" class="selection-summary section-gap"><strong>{{ summary.records.length }}<small>门已发布</small></strong><strong>{{ summary.average ?? '—' }}<small>学分加权均分</small></strong><strong>{{ summary.earnedCredits }}<small>已获学分 / {{ summary.totalCredits }}</small></strong><strong>{{ summary.pendingCount }}<small>门待发布</small></strong></div>
    <section class="panel" v-loading="loading">
      <div class="table-toolbar"><div v-if="!student" class="search-actions wrap"><el-select v-model="classId" filterable placeholder="选择教学班" :disabled="dirty || busy" @change="loadBook"><el-option v-for="item in classes" :key="item.id" :label="`${item.courseName} · ${item.classCode}`" :value="item.id" /></el-select><el-tag v-if="book" :type="published ? 'success' : 'info'">{{ published ? '已发布' : '草稿' }}</el-tag></div><div class="search-actions"><el-button :disabled="busy" @click="refresh">刷新</el-button><el-button :disabled="busy || dirty || (!student && !book)" @click="exportGrades">导出 CSV</el-button></div></div>
      <template v-if="student">
        <el-table :data="summary?.records || []" empty-text="暂无已发布成绩，教师发布后将在这里显示" class="course-table"><el-table-column label="课程" min-width="190"><template #default="{ row }"><strong>{{ row.courseName }}</strong><div class="cell-note">{{ row.classCode }}</div></template></el-table-column><el-table-column prop="teacherName" label="教师" width="100" /><el-table-column prop="semesterName" label="学期" min-width="190" /><el-table-column prop="credits" label="学分" width="70" /><el-table-column prop="usualScore" label="平时" width="80" /><el-table-column prop="examScore" label="期末" width="80" /><el-table-column label="总评" width="90"><template #default="{ row }"><strong :class="Number(row.totalScore) < 60 ? 'score-fail' : 'text-success'">{{ row.totalScore }}</strong></template></el-table-column><el-table-column prop="remark" label="备注" min-width="140" /><el-table-column label="发布时间" min-width="170"><template #default="{ row }">{{ dateTime(row.publishedAt) }}</template></el-table-column></el-table>
        <p class="table-caption">总评 = 平时成绩 × 平时权重 + 期末成绩 × 期末权重。60 分及以上计入已获学分。演示绩点 {{ summary?.gpa ?? '—' }}（低于 60 为 0；60 起为 1，每增加 10 分加 1，上限 4；仅供本系统展示）。</p>
      </template>
      <template v-else-if="book">
        <div class="grade-controls"><div><h2>{{ book.teachingClass.courseName }} · {{ book.teachingClass.classCode }}</h2><p class="muted">{{ book.teachingClass.teacherName }} · {{ rows.length }} 人 · 已录完整 {{ book.completeCount }} 人 · 均分 {{ book.average ?? '—' }} · 及格 {{ book.passCount }} 人</p></div><label>平时权重 <el-input-number v-model="weight" :min="0" :max="100" :precision="0" :disabled="published || busy" @change="dirty = true" /> %</label></div>
        <el-alert :title="published ? '已发布成绩只读。需调整时先撤回，再修改和重新发布。' : '空分数可保存为草稿；发布前必须保存全班完整成绩。录入成绩后学生不能自行退课。'" type="info" :closable="false" class="section-gap" />
        <el-table :data="rows" empty-text="本班暂无有效选课学生" class="course-table grade-table"><el-table-column prop="studentNo" label="学号" min-width="145" /><el-table-column prop="studentName" label="姓名" width="100" /><el-table-column prop="className" label="班级" min-width="135" /><el-table-column label="平时成绩" width="160"><template #default="{ row }"><span v-if="published">{{ row.usualScore ?? '—' }}</span><el-input-number v-else :key="row.revision" v-model="row.usualScore" :min="0" :max="100" :precision="2" :controls="false" :disabled="published || busy" aria-label="平时成绩" @change="dirty = true" /></template></el-table-column><el-table-column label="期末成绩" width="160"><template #default="{ row }"><span v-if="published">{{ row.examScore ?? '—' }}</span><el-input-number v-else :key="row.revision" v-model="row.examScore" :min="0" :max="100" :precision="2" :controls="false" :disabled="published || busy" aria-label="期末成绩" @change="dirty = true" /></template></el-table-column><el-table-column label="总评" width="90"><template #default="{ row }">{{ preview(row) }}</template></el-table-column><el-table-column label="备注" min-width="200"><template #default="{ row }"><el-input v-model="row.remark" maxlength="500" :disabled="published || busy" placeholder="可选" @input="dirty = true" /></template></el-table-column></el-table>
        <div class="grade-actions"><span class="muted">{{ dirty ? '有未保存修改；保存后可切换教学班和导出。' : '当前内容已与服务器同步。' }}</span><el-button v-if="!published" type="primary" :loading="saving" :disabled="busy || !rows.length" @click="save">保存全班草稿</el-button><el-button v-if="!published" type="success" :disabled="busy || dirty || !rows.length || book.completeCount !== rows.length" @click="changeStatus('publish')">发布全班成绩</el-button><el-button v-else type="warning" :disabled="busy" @click="changeStatus('withdraw')">撤回成绩</el-button></div>
      </template><el-empty v-else description="请选择有教学任务的学期与教学班" />
    </section>
  </div>
</template>
