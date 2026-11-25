<script setup>
// 按当前账号的角色展示工作台内容和功能入口。
import { computed, onMounted, ref } from 'vue'
import { authState } from '../stores/auth'
import { academicApi, courseApi, systemApi, learningApi } from '../api/modules'
import { navigation } from '../router'
import { dateTime } from '../utils/academic'

const health = ref(null)
const overview = ref(null)
const loading = ref(true)
const error = ref('')
const currentSemester = ref(null)
const personalStats = ref([])
const recentNotices = ref([]), unreadCount = ref(0)
// 与侧边导航使用同一份配置，展示当前角色可访问的所有功能页。
const shortcuts = computed(() => navigation.filter((item) => item.path !== '/dashboard' && item.roles.includes(authState.user?.role)))
// 将后端统计数据转成工作台显示的卡片。
const stats = computed(() => overview.value ? [
  { label: '学生档案', value: overview.value.studentCount, unit: '人' },
  { label: '任课教师', value: overview.value.teacherCount, unit: '人' },
  { label: '课程资源', value: overview.value.courseCount, unit: '门' },
  { label: '教学班', value: overview.value.teachingClassCount, unit: '个' },
] : personalStats.value)
// 根据登录角色展示不同的操作指引。
const guidance = computed(() => authState.user?.role === 'ADMIN' ? [
  { title: '维护人员与课程', text: '先建立学生、教师档案及基础课程，再安排教学任务。' },
  { title: '设置学期并发布教学班', text: '确认选课窗口、容量与排课，发布后学生即可选择。' },
  { title: '跟踪选退课记录', text: '查看有效人数与历史记录，已有选课历史的数据受到保护。' },
] : authState.user?.role === 'STUDENT' ? [
  { title: '发现课程', text: '按学期浏览课程目录，确认学分、任课教师与上课时间。' },
  { title: '核对个人课表', text: '选课后在课表中按周查看安排，系统会自动检查时间冲突。' },
  { title: '管理选退课记录', text: '在允许时间内退课，历史记录保留，重新选课沿用原记录。' },
] : [
  { title: '查看授课安排', text: '按学期查看本人教学班、上课地点与人数。' },
  { title: '核对学生名单', text: '学生名单只包含当前有效选课，退课后会同步移出。' },
  { title: '保护个人账号', text: '在账号设置中修改密码，定期核对本人授课信息。' },
])

// 按登录角色加载工作台需要的统计及课程信息。
async function load() {
  loading.value = true
  error.value = ''
  try {
    health.value = await systemApi.health()
    // 一个请求拿近期公告，另一个只读取未读结果的总数，避免为显示数字加载整份公告列表。
    const [notices, unread] = await Promise.all([learningApi.notices(false, { page: 1, size: 3 }), learningApi.notices(false, { page: 1, size: 1, unreadOnly: true })])
    recentNotices.value = notices.records; unreadCount.value = unread.total
    const semesters = await academicApi.semesters()
    // 把今天转换为与接口日期相同的字符串格式，在各学期起止范围中寻找当前学期。
    const today = new Date().toLocaleDateString('sv-SE')
    currentSemester.value = semesters.find((item) => today >= item.startDate && today <= item.endDate) || semesters[0] || null
    // 管理员查看全站计数，学生和教师的统计分别由个人选课和本人授课记录计算。
    if (authState.user?.role === 'ADMIN') overview.value = await systemApi.overview()
    else if (authState.user?.role === 'STUDENT') {
      const [mine, catalog] = await Promise.all([academicApi.enrollments(currentSemester.value?.id), courseApi.list(currentSemester.value?.id)])
      const active = mine.filter((item) => item.info.status === 'ENROLLED')
      personalStats.value = [{ label: '本学期已选', value: active.length, unit: '门' }, { label: '已选学分', value: active.reduce((sum, item) => sum + Number(item.info.credits), 0).toFixed(1), unit: '学分' }, { label: '学分上限', value: currentSemester.value?.maxCredits || '—', unit: '学分' }, { label: '已发布教学班', value: catalog.length, unit: '个' }]
    } else {
      const mine = await academicApi.myClasses(currentSemester.value?.id)
      personalStats.value = [{ label: '本学期授课', value: mine.length, unit: '个班' }, { label: '有效选课', value: mine.reduce((sum, item) => sum + item.selectedCount, 0), unit: '人次' }, { label: '已发布', value: mine.filter((item) => item.status === 'PUBLISHED').length, unit: '个班' }, { label: '总容量', value: mine.reduce((sum, item) => sum + item.capacity, 0), unit: '人次' }]
    }
  } catch (exception) { error.value = exception.message }
  finally { loading.value = false }
}
// 页面首次打开时读取工作台数据。
onMounted(load)
</script>

<!-- 功能入口独立于统计加载，页面打开后即可使用。 -->
<template>
  <div>
    <div class="page-heading"><div><span class="eyebrow">工作台 / OVERVIEW</span><h1>你好，{{ authState.user?.displayName }}。</h1></div><span class="stage-badge">{{ currentSemester?.name || '教学工作台' }}</span></div>
    <section class="panel section-gap" aria-labelledby="workbench-features">
      <div class="panel-heading"><h2 id="workbench-features">全部功能</h2></div>
      <div class="shortcut-grid">
        <router-link v-for="item in shortcuts" :key="item.path" :to="item.path" class="shortcut"
          :aria-label="`进入${item.title}`" :aria-describedby="`shortcut-${item.path.slice(1)}`">
          <span class="shortcut-heading">
            <span class="shortcut-mark" aria-hidden="true">{{ item.mark }}</span>
            <strong>{{ item.title }}</strong>
          </span>
          <span :id="`shortcut-${item.path.slice(1)}`" class="shortcut-description">
            {{ authState.user?.role === 'STUDENT' ? item.studentDescription ?? item.description : item.description }}
          </span>
          <span class="shortcut-action" aria-hidden="true">进入 <span>↗</span></span>
        </router-link>
      </div>
    </section>
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="section-gap"><template #default><el-button text @click="load">重新加载</el-button></template></el-alert>
      <section v-if="stats.length" class="stat-grid">
        <article v-for="(stat, index) in stats" :key="stat.label" class="stat-card"><span class="stat-index">0{{ index + 1 }}</span><p>{{ stat.label }}</p><strong>{{ stat.value }}<small>{{ stat.unit }}</small></strong></article>
      </section>
      <section class="panel section-gap"><div class="panel-heading"><div><h2>最新公告 <el-tag v-if="unreadCount" type="warning" size="small">{{ unreadCount }} 条未读</el-tag></h2><p>显示面向当前角色的有效通知。</p></div><router-link to="/announcements"><el-button text type="primary">查看全部</el-button></router-link></div><el-empty v-if="!recentNotices.length" description="暂无有效公告" :image-size="55" /><router-link v-for="notice in recentNotices" :key="notice.id" to="/announcements" class="dashboard-notice"><strong>{{ notice.title }}</strong><span class="muted">{{ dateTime(notice.publishedAt) }}</span></router-link></section>
      <div class="dashboard-bottom">
        <section class="panel"><div class="panel-heading"><h2>使用指引</h2><span class="subtle-label">GET STARTED</span></div>
          <ul class="progress-list"><li v-for="item in guidance" :key="item.title"><span class="progress-dot done"></span><div><strong>{{ item.title }}</strong><p>{{ item.text }}</p></div></li></ul>
        </section>
        <section class="panel service-panel"><span class="eyebrow">学期信息</span><div class="service-state"><span class="status-dot" :class="{ offline: !health }"></span>{{ health?.status === 'UP' ? '系统运行正常' : '等待连接' }}</div><p>{{ currentSemester?.name || '请先由管理员建立学期' }}</p><dl><div><dt>选课开始</dt><dd>{{ dateTime(currentSemester?.selectionStart) }}</dd></div><div><dt>选课结束</dt><dd>{{ dateTime(currentSemester?.selectionEnd) }}</dd></div><div><dt>每人学分上限</dt><dd>{{ currentSemester?.maxCredits ?? '—' }}</dd></div></dl></section>
      </div>
    </div>
  </div>
</template>
