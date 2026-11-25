-- 为 H2 集成测试数据库补充常用查询索引。
-- 支持按教学班和状态快速查询学生人数及选课记录。
CREATE INDEX IF NOT EXISTS ix_enrollment_class ON enrollment(teaching_class_id, status);
CREATE INDEX IF NOT EXISTS ix_schedule_class ON course_schedule(teaching_class_id);
-- 按学期查看目录时经常同时筛选发布状态，两列放在一个索引中便于这类查询。
CREATE INDEX IF NOT EXISTS ix_class_semester ON teaching_class(semester_id, status);
CREATE INDEX IF NOT EXISTS ix_class_course ON teaching_class(course_id);
CREATE INDEX IF NOT EXISTS ix_class_teacher ON teaching_class(teacher_id);
-- 支持按公告状态、受众和排序条件查找公告。
CREATE INDEX IF NOT EXISTS ix_notice_visible ON announcement(status, audience, pinned DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_notice_read_user ON announcement_read(user_id);
