<script setup>
// 展示教师所授教学班，可按学期查找并查看选课学生名单。
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { academicApi } from '../api/modules'
import { classStatuses, dateTime, scheduleText } from '../utils/academic'
import { downloadCsv } from '../utils/export'

const route = useRoute()
// 用路由区分授课列表入口和学生名单入口。
const isRosterPage = computed(() => route.path === '/roster')
const semesters = ref([])
const semesterId = ref()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const selectedId = ref()
const roster = ref([])
const rosterLoading = ref(false)
const rosterError = ref('')
const selectedClass = computed(() => rows.value.find((item) => item.id === selectedId.value))
let rosterVersion = 0

// 按当前教学班加载学生名单，旧请求返回时忽略其结果。
async function loadRoster() {
  // 切班后清掉旧名单并更新请求编号。较早的请求即使最后返回，也不会覆盖当前班级的名单。
  const version = ++rosterVersion
  roster.value = []
  rosterError.value = ''
  if (!selectedId.value) return
  rosterLoading.value = true
  try {
    const result = await academicApi.roster(selectedId.value)
    if (version === rosterVersion) roster.value = result
  } catch (exception) { if (version === rosterVersion) rosterError.value = exception.message }
  finally { if (version === rosterVersion) rosterLoading.value = false }
}
// 查询本学期授课班级，班级选择失效时切换到可用班级。
async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await academicApi.myClasses(semesterId.value)
    // 换学期后，如果原班级仍在结果里就继续选中它。否则切到第一项，无课可选时清空名单。
    if (!rows.value.some((item) => item.id === selectedId.value)) selectedId.value = rows.value[0]?.id
    await loadRoster()
  } catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 记录点击的班级编号，再刷新该班级的学生名单。
function choose(row) { selectedId.value = row.id; loadRoster() }
// 先读取学期选项，再加载教师的班级。
onMounted(async () => {
  try { semesters.value = await academicApi.semesters(); semesterId.value = semesters.value[0]?.id; await load() }
  catch (exception) { error.value = exception.message }
})
</script>

<!-- 上方展示授课班级，下方展示当前选中班级的学生名单。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">教师工作 / TEACHING</span><h1>{{ isRosterPage ? '学生名单' : '我的教学班' }}</h1><p>仅展示本人承担的教学任务与有效选课学生。</p></div><el-select v-model="semesterId" placeholder="全部学期" clearable class="heading-select" @change="load"><el-option v-for="semester in semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
    <section v-if="!isRosterPage" class="panel section-gap">
      <div class="panel-heading"><h2>授课安排 · {{ rows.length }} 个教学班</h2><el-button :loading="loading" @click="load">刷新</el-button></div>
      <el-table v-loading="loading" :data="rows" empty-text="本学期暂无教学任务" class="course-table">
        <el-table-column label="课程" min-width="190"><template #default="{ row }"><strong>{{ row.courseName }}</strong><div class="cell-note">{{ row.classCode }} · {{ row.credits }} 学分</div></template></el-table-column>
        <el-table-column label="上课安排" min-width="280"><template #default="{ row }"><div v-for="slot in row.schedules" :key="slot.id" class="cell-note">{{ scheduleText(slot) }}</div></template></el-table-column>
        <el-table-column label="选课人数" width="110"><template #default="{ row }">{{ row.selectedCount }} / {{ row.capacity }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }">{{ classStatuses[row.status] }}</template></el-table-column>
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button text type="primary" @click="choose(row)">查看名单</el-button></template></el-table-column>
      </el-table>
    </section>
    <section class="panel">
      <div class="panel-heading"><div><h2>{{ selectedClass ? `${selectedClass.courseName} · 学生名单` : '学生名单' }}</h2><p>仅统计当前有效选课，已退课记录不计入名单。</p></div><el-select v-model="selectedId" placeholder="请选择教学班" class="heading-select" @change="loadRoster"><el-option v-for="row in rows" :key="row.id" :label="`${row.courseName} · ${row.classCode}`" :value="row.id" /></el-select></div>
      <el-alert v-if="rosterError" :title="rosterError" type="error" :closable="false" class="section-gap" />
      <el-table v-loading="rosterLoading" :data="roster" empty-text="暂无有效选课学生" class="course-table"><el-table-column type="index" label="序号" width="70" /><el-table-column prop="studentNo" label="学号" min-width="150" /><el-table-column prop="displayName" label="姓名" min-width="130" /><el-table-column prop="major" label="专业" min-width="180" /><el-table-column prop="className" label="班级" min-width="150" /><el-table-column label="选课时间" min-width="180"><template #default="{ row }">{{ dateTime(row.selectedAt) }}</template></el-table-column></el-table>
      <div class="grade-actions"><p class="table-caption">当前教学班共 {{ roster.length }} 名学生</p><el-button :disabled="!selectedId || rosterLoading" @click="downloadCsv('teaching', 'roster', { classId: selectedId }, `${selectedClass?.classCode}_学生名单`)">导出名单 CSV</el-button></div>
    </section>
  </div>
</template>
