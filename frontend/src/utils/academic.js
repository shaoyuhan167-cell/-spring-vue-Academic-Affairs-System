// 统一课程状态和时间的显示格式，并提供课表冲突判断所需的工具。
export const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export const classStatuses = { DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已关闭' }
export const enrollmentStatuses = { ENROLLED: '已选', DROPPED: '已退' }
// 把接口返回的日期时间转成短格式，缺少时间时显示占位符。
export const dateTime = (value) => value ? value.replace('T', ' ').slice(0, 16) : '—'
// 将一段课表信息拼成可读的星期、节次、周次与教室。
export function scheduleText(slot) {
  return `${weekdays[slot.weekday - 1]} 第${slot.startPeriod}—${slot.endPeriod}节 · ${slot.startWeek}—${slot.endWeek}周 · ${slot.classroom}`
}

// 找出两组排课中首个发生重叠的时段，生成具体冲突提示。
export function scheduleOverlapText(first, second) {
  const range = (start, end) => start === end ? String(start) : `${start}～${end}`
  for (const a of first) {
    for (const b of second) {
      // 先比较是否同一天，再寻找两段节次和周次同时相交的部分。用重叠范围生成可读的冲突说明。
      if (a.weekday !== b.weekday) continue
      const firstPeriod = Math.max(a.startPeriod, b.startPeriod)
      const lastPeriod = Math.min(a.endPeriod, b.endPeriod)
      const firstWeek = Math.max(a.startWeek, b.startWeek)
      const lastWeek = Math.min(a.endWeek, b.endWeek)
      if (firstPeriod <= lastPeriod && firstWeek <= lastWeek) {
        return `${weekdays[a.weekday - 1]} 第${range(firstPeriod, lastPeriod)}节，第${range(firstWeek, lastWeek)}周`
      }
    }
  }
  return null
}

// 将选课摘要与课表合并为列表更容易读取的结构。
export function flattenEnrollment(view) { return { ...view.info, schedules: view.schedules } }
