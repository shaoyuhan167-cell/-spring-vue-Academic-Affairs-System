<script setup>
// 按学期和周次加载课表，将课程排在对应的星期和节次上。
import { computed, onMounted, ref } from 'vue'
import { academicApi } from '../api/modules'
import { weekdays } from '../utils/academic'

const semesters = ref([])
const semesterId = ref()
// 周次是接口的筛选条件，页面只渲染该周实际发生的课程。换学期后从开学日重新计算初始周。
const week = ref(1)
const items = ref([])
const loading = ref(false)
const error = ref('')
// 同一教学班可能有多段课堂安排，按教学班编号去重后再计数。
const courseCount = computed(() => new Set(items.value.map((item) => item.teachingClassId)).size)
// 先生成 7 天、每天 12 节的格子，再用课程的起止节次决定它占几行。
const cells = Array.from({ length: 84 }, (_, index) => ({ day: index % 7 + 1, period: Math.floor(index / 7) + 1 }))
// 根据当前学期与周次获取课表中的上课时段。
async function load() {
  loading.value = true
  error.value = ''
  try { items.value = await academicApi.timetable({ semesterId: semesterId.value, week: week.value }) }
  catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 用学期开学日期估算默认周次，然后加载该周的课表。
function semesterChanged() {
  const period = semesters.value.find((item) => item.id === semesterId.value)
  // 每 7 天算一周，并将结果限制在课表支持的第 1 至第 30 周。
  if (period) week.value = Math.max(1, Math.min(30, Math.floor((Date.now() - new Date(`${period.startDate}T00:00:00`).getTime()) / 604800000) + 1))
  load()
}
// 页面打开时先选中列表中的第一个学期。
onMounted(async () => {
  try { semesters.value = await academicApi.semesters(); semesterId.value = semesters.value[0]?.id; semesterChanged() }
  catch (exception) { error.value = exception.message }
})
</script>

<!-- 左侧标节次、顶部标星期，课程按开始和结束节次跨越一个或多个格子。 -->
<!-- 网格以首行为星期标题、首列为节次标题。模板为课程坐标加上这些偏移，并让结束节次也被课程卡片覆盖。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">学习安排 / TIMETABLE</span><h1>我的课表</h1><p>第 {{ week }} 周 · {{ courseCount }} 门课程，退课或选课后刷新即可同步。</p></div><router-link to="/courses"><el-button>前往选课</el-button></router-link></div>
    <section class="panel">
      <div class="table-toolbar"><div class="search-actions"><el-select v-model="semesterId" placeholder="选择学期" @change="semesterChanged"><el-option v-for="semester in semesters" :key="semester.id" :label="semester.name" :value="semester.id" /></el-select><label class="week-picker">周次 <el-input-number v-model="week" :min="1" :max="30" @change="load" /></label></div><el-button :loading="loading" @click="load">刷新课表</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="section-gap" />
      <el-alert v-if="!loading && !items.length && !error" title="本周暂无课程。可以切换周次，或前往课程目录选课。" type="info" :closable="false" class="section-gap" />
      <p class="table-caption section-gap">窄屏下可左右滚动查看完整一周，课程按本周实际安排显示。</p>
      <div v-loading="loading" class="timetable-scroll"><div class="timetable-grid">
        <div class="timetable-corner">节次</div><div v-for="(day, index) in weekdays" :key="day" class="timetable-day" :style="{ gridColumn: index + 2 }">{{ day }}</div>
        <div v-for="period in 12" :key="`period-${period}`" class="timetable-period" :style="{ gridRow: period + 1 }">第 {{ period }} 节</div>
        <div v-for="cell in cells" :key="`${cell.day}-${cell.period}`" class="timetable-cell" :style="{ gridColumn: cell.day + 1, gridRow: cell.period + 1 }"></div>
        <article v-for="(item, index) in items" :key="`${item.teachingClassId}-${index}`" class="timetable-course" :class="`course-color-${item.teachingClassId % 4}`" :style="{ gridColumn: item.weekday + 1, gridRow: `${item.startPeriod + 1} / ${item.endPeriod + 2}` }"><strong>{{ item.courseName }}</strong><span>{{ item.teacherName }}</span><span>{{ item.classroom }}</span><small>{{ item.startWeek }}—{{ item.endWeek }} 周 · {{ item.startPeriod }}—{{ item.endPeriod }} 节</small></article>
      </div></div>
    </section>
  </div>
</template>
