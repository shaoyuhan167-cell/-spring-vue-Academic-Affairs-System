// 按业务模块集中定义 API 请求，页面通过这些方法访问后端。
import http from './http'

// 登录、会话、密码和本机账号切换相关请求。
export const authApi = {
  login: (credentials) => http.post('/auth/login', credentials),
  localAccounts: () => http.get('/auth/local-accounts'),
  localLogin: (username) => http.post('/auth/local-login', { username }),
  me: () => http.get('/auth/me'),
  logout: () => http.post('/auth/logout'),
  changePassword: (data) => http.post('/auth/password', data),
}

// 健康检查和管理员工作台统计请求。
export const systemApi = {
  health: () => http.get('/system/health'),
  overview: () => http.get('/system/overview'),
}

// 查询登录后可访问的课程目录，可按学期过滤。
export const courseApi = {
  list: (semesterId) => http.get('/courses', { params: { semesterId } }),
}

// 管理员对基础资源和教学班的增删改查。
export const adminApi = {
  list: (resource, params) => http.get(`/admin/${resource}`, { params }),
  create: (resource, data) => http.post(`/admin/${resource}`, data),
  update: (resource, id, data) => http.put(`/admin/${resource}/${id}`, data),
  remove: (resource, id) => http.delete(`/admin/${resource}/${id}`),
  resetPassword: (resource, id, newPassword) => http.post(`/admin/${resource}/${id}/password`, { newPassword }),
  options: () => http.get('/admin/options'),
}

// 学期、选退课、课表、授课和名单查询。
export const academicApi = {
  semesters: () => http.get('/semesters'),
  enrollments: (semesterId) => http.get('/enrollments', { params: { semesterId } }),
  enroll: (teachingClassId) => http.post('/enrollments', { teachingClassId }),
  enrollBatch: (teachingClassIds) => http.post('/enrollments/batch', { teachingClassIds }),
  drop: (id) => http.delete(`/enrollments/${id}`),
  timetable: (params) => http.get('/timetable', { params }),
  myClasses: (semesterId) => http.get('/teaching/me/classes', { params: { semesterId } }),
  roster: (id) => http.get(`/teaching/classes/${id}/students`),
}

// 成绩管理、个人资料与公告功能使用的接口。
export const learningApi = {
  classes: (semesterId) => http.get('/grades/classes', { params: { semesterId } }),
  book: (id) => http.get(`/grades/classes/${id}`),
  saveGrades: (id, body) => http.put(`/grades/classes/${id}`, body),
  // 发布和撤回只是在同一条成绩接口路径上使用不同动作，提交参数始终包含全班记录和版本。
  gradeStatus: (id, action, body) => http.post(`/grades/classes/${id}/${action}`, body),
  myGrades: (semesterId) => http.get('/grades/mine', { params: { semesterId } }),
  profile: () => http.get('/profile'),
  saveProfile: (body) => http.put('/profile', body),
  notices: (admin, params) => http.get(admin ? '/admin/announcements' : '/announcements', { params }),
  notice: (id) => http.get(`/announcements/${id}`),
  readNotice: (id, revision) => http.post(`/announcements/${id}/read`, { revision }),
  createNotice: (body) => http.post('/admin/announcements', body),
  updateNotice: (id, body) => http.put(`/admin/announcements/${id}`, body),
  removeNotice: (id, revision) => http.delete(`/admin/announcements/${id}`, { params: { revision } }),
}

// 将导出响应作为文件处理，并给生成文件留出更长的请求时间。
export const exportApi = (scope, resource, params) => http.get(`/exports/${scope}/${resource}`, { params, responseType: 'blob', timeout: 60000 })
