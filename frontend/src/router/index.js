// 定义页面路由和可访问角色，并在跳转时检查当前登录身份。
import { createRouter, createWebHistory } from 'vue-router'
import { authState, clearSession, restoreSession } from '../stores/auth'

const allRoles = ['ADMIN', 'STUDENT', 'TEACHER']
// 多个管理路径指向同一页面组件。路由元信息保存资源键，组件据此决定要读写哪类数据。
const managementResources = { '/students': 'students', '/teachers': 'teachers', '/course-management': 'courses', '/semesters': 'semesters' }
// 管理资源复用表单页，其余路径指向各自页面，并按需加载。
function viewFor(path) {
  if (managementResources[path]) return () => import('../views/ResourceManagementView.vue')
  if (path === '/dashboard') return () => import('../views/DashboardView.vue')
  if (path === '/courses') return () => import('../views/CourseListView.vue')
  if (path === '/teaching-classes') return () => import('../views/ClassManagementView.vue')
  if (['/my-courses', '/enrollments'].includes(path)) return () => import('../views/EnrollmentView.vue')
  if (['/teaching', '/roster'].includes(path)) return () => import('../views/TeachingView.vue')
  if (path === '/timetable') return () => import('../views/TimetableView.vue')
  if (path === '/grades') return () => import('../views/GradeView.vue')
  if (path === '/audit-logs') return () => import('../views/AuditLogView.vue')
  if (path === '/profile') return () => import('../views/ProfileView.vue')
  if (['/announcements', '/announcement-management'].includes(path)) return () => import('../views/AnnouncementView.vue')
  return () => import('../views/AccountView.vue')
}

// 菜单和路由由同一份配置生成，让页面入口和访问角色对应起来。
export const navigation = [
  { path: '/dashboard', title: '工作台', mark: '概', roles: allRoles },
  { path: '/courses', title: '课程目录', mark: '课', roles: allRoles, description: '浏览已发布的教学班和剩余名额。', studentDescription: '查询课程，支持单门和批量选课。' },
  { path: '/my-courses', title: '我的选课', mark: '选', roles: ['STUDENT'], description: '查看已选课程和历史记录，办理退课。', fields: ['课程信息', '选课状态', '选课时间', '退课操作'] },
  { path: '/timetable', title: '我的课表', mark: '表', roles: ['STUDENT'], description: '按星期与节次展示当前学期的已选课程。', fields: ['学期切换', '周次筛选', '课程时间', '上课地点'] },
  { path: '/students', title: '学生管理', mark: '学', roles: ['ADMIN'], description: '维护学生账号与学籍档案。', fields: ['学号', '姓名', '专业', '班级', '账号状态'] },
  { path: '/teachers', title: '教师管理', mark: '师', roles: ['ADMIN'], description: '维护教师账号与所属院系信息。', fields: ['工号', '姓名', '院系', '账号状态'] },
  { path: '/course-management', title: '课程管理', mark: '库', roles: ['ADMIN'], description: '维护课程基础信息，为教学班开设提供课程数据。', fields: ['课程编号', '课程名称', '学分', '学时', '课程简介'] },
  { path: '/teaching-classes', title: '开课管理', mark: '班', roles: ['ADMIN'], description: '排课、发布教学班并查看学生名单。', fields: ['教学班编号', '学期', '教师', '容量', '排课', '发布状态'] },
  { path: '/semesters', title: '学期设置', mark: '期', roles: ['ADMIN'], description: '配置学期和选课时间窗口，统一管理学分上限。', fields: ['学期名称', '起止日期', '选课时间', '学分上限'] },
  { path: '/enrollments', title: '选课记录', mark: '录', roles: ['ADMIN'], description: '查询学生选退课情况并进行汇总。', fields: ['学生', '教学班', '选退课状态', '操作时间'] },
  { path: '/teaching', title: '我的教学班', mark: '教', roles: ['TEACHER'], description: '按学期查看本人承担的教学班和上课安排。', fields: ['课程', '学期', '上课时间', '地点', '选课人数'] },
  { path: '/roster', title: '学生名单', mark: '名', roles: ['TEACHER'], description: '查询本人教学班的选课学生名单。', fields: ['教学班筛选', '学号', '姓名', '专业', '班级'] },
  { path: '/grades', title: '成绩中心', mark: '绩', roles: allRoles, description: '录入、发布和撤回教学班成绩。', studentDescription: '查询已发布成绩和已获学分。' },
  { path: '/announcements', title: '公告通知', mark: '告', roles: allRoles, description: '查看通知并确认已读。' },
  { path: '/announcement-management', title: '公告管理', mark: '发', roles: ['ADMIN'], description: '发布、置顶或撤回公告。' },
  { path: '/audit-logs', title: '审计日志', mark: '审', roles: ['ADMIN'], description: '查询成绩发布、账号变更等操作记录。' },
  { path: '/profile', title: '个人资料', mark: '我', roles: allRoles, description: '查看个人信息，更新联系方式和简介。' },
  { path: '/account', title: '账号设置', mark: '密', roles: allRoles, description: '修改当前账号的登录密码。' },
]

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || (to.path !== from.path ? { top: 0 } : false)
  },
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { title: '登录', public: true } },
    {
      path: '/', component: () => import('../layouts/AppLayout.vue'), redirect: '/dashboard',
      children: navigation.map((item) => ({
        path: item.path.slice(1),
        component: viewFor(item.path),
        meta: { ...item, resource: managementResources[item.path] },
      })),
    },
    { path: '/:pathMatch(.*)*', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面不存在', public: true } },
  ],
})

// 进入页面前检查登录和访问角色，并同步浏览器标题。
router.beforeEach(async (to) => {
  // 先确认登录身份，再决定是进入目标页、登录页还是工作台。
  await restoreSession()
  document.title = `${to.meta.title || '工作台'} · 学生选课系统`
  // 未登录访问受限页面时保留原路径。登录完成后可以返回原页面，实际业务权限仍由 API 校验。
  if (!to.meta.public && !authState.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && authState.user) return '/dashboard'
  if (to.meta.roles && !to.meta.roles.includes(authState.user?.role)) return '/dashboard'
})

// 收到接口的会话失效通知时清理前端身份并回到登录页。
window.addEventListener('session-expired', () => {
  clearSession()
  if (router.currentRoute.value.path !== '/login') router.replace('/login')
})

export default router
