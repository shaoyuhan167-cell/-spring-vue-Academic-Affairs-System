// 为共用的资源管理页面配置各类表单字段、表格列和相关操作。
const text = (key, label, max = 100, extra = {}) => ({ key, label, type: 'text', required: true, max, ...extra })
// 初始密码只用于创建账号。编辑已有账号的资料时，通过单独的重置密码流程处理密码变化。
const password = { key: 'initialPassword', label: '初始密码', type: 'password', required: true, createOnly: true, max: 72 }
const enabled = { key: 'enabled', label: '账号启用', type: 'switch', default: true }

export const resources = {
  students: {
    title: '学生管理', noun: '学生', description: '维护学生学籍与登录账号，停用或重置密码后旧登录立即失效。', person: true,
    fields: [text('studentNo', '学号', 30, { immutable: true }), text('displayName', '姓名', 50), text('major', '专业'), text('className', '班级'), enabled, password],
    columns: [{ key: 'studentNo', label: '学号' }, { key: 'displayName', label: '姓名' }, { key: 'major', label: '专业' }, { key: 'className', label: '班级' }],
  },
  teachers: {
    title: '教师管理', noun: '教师', description: '维护任课教师及所属院系，账号使用工号登录。', person: true,
    fields: [text('teacherNo', '工号', 30, { immutable: true }), text('displayName', '姓名', 50), text('department', '院系'), enabled, password],
    columns: [{ key: 'teacherNo', label: '工号' }, { key: 'displayName', label: '姓名' }, { key: 'department', label: '院系' }],
  },
  courses: {
    title: '课程管理', noun: '课程', description: '维护基础课程。课程被开课引用后，仅能修改名称和简介。',
    fields: [text('courseCode', '课程编号', 30), text('name', '课程名称'), { key: 'credits', label: '学分', type: 'number', min: 0.1, max: 30, step: 0.5, precision: 1, default: 2, required: true }, { key: 'hours', label: '学时', type: 'number', min: 1, max: 512, default: 32, required: true }, text('description', '课程简介', 1000, { type: 'textarea', required: false })],
    columns: [{ key: 'courseCode', label: '课程编号' }, { key: 'name', label: '课程名称' }, { key: 'credits', label: '学分' }, { key: 'hours', label: '学时' }, { key: 'description', label: '课程简介', width: 280 }],
  },
  // 把表格列、表单字段和校验限制放在一起配置，共用页面能从同一组定义生成两种界面。
  semesters: {
    title: '学期设置', noun: '学期', description: '统一设置学期日期、选退课时间窗口及每位学生的学分上限。',
    fields: [text('name', '学期名称', 50), { key: 'startDate', label: '开始日期', type: 'date', required: true }, { key: 'endDate', label: '结束日期', type: 'date', required: true }, { key: 'selectionStart', label: '选课开始时间', type: 'datetime', required: true }, { key: 'selectionEnd', label: '选课结束时间', type: 'datetime', required: true }, { key: 'maxCredits', label: '学分上限', type: 'number', min: 0.1, max: 100, precision: 1, default: 30, required: true }],
    columns: [{ key: 'name', label: '学期名称', width: 240 }, { key: 'startDate', label: '开始日期' }, { key: 'endDate', label: '结束日期' }, { key: 'selectionStart', label: '选课开始', type: 'datetime', width: 180 }, { key: 'selectionEnd', label: '选课结束', type: 'datetime', width: 180 }, { key: 'maxCredits', label: '学分上限' }],
  },
}
