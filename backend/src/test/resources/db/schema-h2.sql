-- 集成测试使用的 H2 表结构。
-- 登录账号集中存放在这里，学生档案和教师档案都通过账号编号关联。
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_role CHECK (role IN ('ADMIN', 'STUDENT', 'TEACHER'))
);

-- 学生档案保存学号、专业和班级等学籍信息。
CREATE TABLE IF NOT EXISTS student (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    student_no VARCHAR(30) NOT NULL UNIQUE,
    major VARCHAR(100) NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- 教师档案保存工号和院系，开设教学班时引用这里的教师。
CREATE TABLE IF NOT EXISTS teacher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    teacher_no VARCHAR(30) NOT NULL UNIQUE,
    department VARCHAR(100) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- 课程库定义课程名称、学分和学时，一门课程可以对应多个教学班。
CREATE TABLE IF NOT EXISTS course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    credits DECIMAL(4,1) NOT NULL,
    hours INT NOT NULL,
    description VARCHAR(1000),
    CONSTRAINT ck_course_credits CHECK (credits > 0),
    CONSTRAINT ck_course_hours CHECK (hours > 0)
);

-- 学期同时记录教学日期、允许选退课的时间段和学分上限。
CREATE TABLE IF NOT EXISTS semester (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    selection_start TIMESTAMP NOT NULL,
    selection_end TIMESTAMP NOT NULL,
    max_credits DECIMAL(5,1) NOT NULL DEFAULT 30.0,
    CONSTRAINT ck_semester_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_selection_dates CHECK (selection_end >= selection_start)
);

-- 教学班表示一门课程在某学期的一次具体开设，包含任课教师、容量和发布状态。
CREATE TABLE IF NOT EXISTS teaching_class (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_code VARCHAR(40) NOT NULL UNIQUE,
    course_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    semester_id BIGINT NOT NULL,
    capacity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    FOREIGN KEY (course_id) REFERENCES course(id),
    FOREIGN KEY (teacher_id) REFERENCES teacher(id),
    FOREIGN KEY (semester_id) REFERENCES semester(id),
    CONSTRAINT ck_class_capacity CHECK (capacity > 0),
    CONSTRAINT ck_class_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED'))
);

-- 教学班可以有多段上课安排，每段记录星期、节次、周次和教室。
CREATE TABLE IF NOT EXISTS course_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    teaching_class_id BIGINT NOT NULL,
    weekday INT NOT NULL,
    start_period INT NOT NULL,
    end_period INT NOT NULL,
    start_week INT NOT NULL,
    end_week INT NOT NULL,
    classroom VARCHAR(100) NOT NULL,
    FOREIGN KEY (teaching_class_id) REFERENCES teaching_class(id),
    CONSTRAINT ck_schedule_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_schedule_period CHECK (start_period >= 1 AND end_period >= start_period),
    CONSTRAINT ck_schedule_week CHECK (start_week >= 1 AND end_week >= start_week)
);

-- 连接学生和教学班。退课通过状态和退课时间保留记录，再选时可以恢复。
CREATE TABLE IF NOT EXISTS enrollment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    teaching_class_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ENROLLED',
    selected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dropped_at TIMESTAMP NULL,
    FOREIGN KEY (student_id) REFERENCES student(id),
    FOREIGN KEY (teaching_class_id) REFERENCES teaching_class(id),
    -- 同一学生与教学班只能有一条记录。重新选过已退的班级时更新原记录，并写入新的选课时间。
    CONSTRAINT uk_enrollment UNIQUE (student_id, teaching_class_id),
    CONSTRAINT ck_enrollment_status CHECK (status IN ('ENROLLED', 'DROPPED'))
);
-- 成绩关联到一条选课记录，保存平时分、考试分、权重和发布状态。
CREATE TABLE IF NOT EXISTS student_grade (
    enrollment_id BIGINT PRIMARY KEY,
    usual_score DECIMAL(5,2),
    exam_score DECIMAL(5,2),
    usual_weight INT NOT NULL DEFAULT 30,
    total_score DECIMAL(5,2),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    remark VARCHAR(500) NOT NULL DEFAULT '',
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    revision INT NOT NULL DEFAULT 1,
    FOREIGN KEY (enrollment_id) REFERENCES enrollment(id),
    FOREIGN KEY (updated_by) REFERENCES sys_user(id),
    -- 允许草稿暂时缺少分数。已有分数要处于有效范围内，发布完整性由成绩服务检查。
    CONSTRAINT ck_grade_scores CHECK ((usual_score IS NULL OR usual_score BETWEEN 0 AND 100) AND (exam_score IS NULL OR exam_score BETWEEN 0 AND 100)),
    CONSTRAINT ck_grade_weight CHECK (usual_weight BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_total CHECK (total_score IS NULL OR total_score BETWEEN 0 AND 100),
    CONSTRAINT ck_grade_status CHECK (status IN ('DRAFT','PUBLISHED'))
);

-- 个人资料单独存放，供用户维护联系方式及个人简介。
CREATE TABLE IF NOT EXISTS user_profile (
    user_id BIGINT PRIMARY KEY,
    email VARCHAR(120) NOT NULL DEFAULT '',
    phone VARCHAR(30) NOT NULL DEFAULT '',
    bio VARCHAR(500) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- 公告可以设定面向的角色、显示时间及发布状态。
CREATE TABLE IF NOT EXISTS announcement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    content VARCHAR(5000) NOT NULL,
    audience VARCHAR(20) NOT NULL DEFAULT 'ALL',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    visible_from TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    author_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revision INT NOT NULL DEFAULT 1,
    FOREIGN KEY (author_id) REFERENCES sys_user(id),
    CONSTRAINT ck_notice_audience CHECK (audience IN ('ALL','ADMIN','TEACHER','STUDENT')),
    CONSTRAINT ck_notice_status CHECK (status IN ('DRAFT','PUBLISHED'))
);

-- 用账号与公告关联已读记录，并记录该用户读过的公告版本。
CREATE TABLE IF NOT EXISTS announcement_read (
    announcement_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notice_revision INT NOT NULL,
    read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 每位读者对同一公告只有一条已读记录。打开更新版本时改写该记录中的版本与时间。
    PRIMARY KEY (announcement_id,user_id),
    FOREIGN KEY (announcement_id) REFERENCES announcement(id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- 审计日志记录谁在何时对哪个对象执行了什么操作。
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    actor_role VARCHAR(20) NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT,
    detail VARCHAR(1000) NOT NULL DEFAULT '',
    client_ip VARCHAR(45) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX ix_audit_created (created_at),
    FOREIGN KEY (actor_id) REFERENCES sys_user(id)
);
