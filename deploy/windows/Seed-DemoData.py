"""通过实际业务 API 补充模拟数据，保留已有记录，完成后重复运行只读检查。"""

import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPCookieProcessor


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / '.runtime'
RELEASE = ROOT / 'release' / 'windows'
STATE_FILE = RUNTIME / 'mock-data-manifest.json'
SETTINGS_FILE = RUNTIME / 'mock-data-settings.json'
ACCOUNT_FILE = RELEASE / '模拟数据账号.txt'
SUMMARY_FILE = RUNTIME / 'mock-data-summary.json'


# 保存业务接口失败的 HTTP 状态及提示，方便脚本判断如何处理。
class ApiError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(f'HTTP {status}：{message}')
        self.status = status


# 封装 API 请求和登录会话，自动携带 Cookie 及写请求所需的 CSRF 令牌。
class Client:
    def __init__(self, base_url):
        self.base_url = base_url
        self.opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None

    # 发送 API 请求，解包响应中的业务数据并统一处理错误。
    def request(self, method, path, body=None, params=None):
        if params:
            path += '?' + urlencode({key: value for key, value in params.items() if value is not None})
        headers = {'Accept': 'application/json'}
        payload = None
        # 普通查询直接读取，修改业务数据前先向后端取得防伪令牌，模拟浏览器正常提交过程。
        if method not in ('GET', 'HEAD', 'OPTIONS'):
            if self.csrf is None:
                self.csrf = self.request('GET', '/auth/csrf')
            headers[self.csrf['headerName']] = self.csrf['token']
        if body is not None:
            headers['Content-Type'] = 'application/json; charset=utf-8'
            payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
        request = Request(self.base_url + '/api' + path, data=payload, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                result = json.load(response)
        except HTTPError as error:
            try:
                message = json.load(error).get('message', '请求失败')
            except (ValueError, UnicodeError):
                message = '请求失败'
            raise ApiError(error.code, message) from None
        if result['code'] != 0:
            raise ApiError(result['code'], result.get('message', '请求失败'))
        return result['data']

    # 以指定身份登录，后续请求复用当前会话。
    def login(self, username, password):
        user = self.request('POST', '/auth/login', {'username': username, 'password': password})
        self.csrf = None
        return user

    # 退出当前身份，让客户端可以继续登录其他演示账号。
    def logout(self):
        self.request('POST', '/auth/logout')
        self.csrf = None


# 用 UTF-8 将运行数据写入 JSON，便于后续重复执行和核对。
def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


# 调整本机文件权限，限制演示账号信息的访问范围。
def private_file(path):
    """仅向当前用户、系统和本机管理员开放模拟账号文件。"""
    identity = subprocess.run(['powershell.exe', '-NoProfile', '-Command',
        '[Security.Principal.WindowsIdentity]::GetCurrent().User.Value'], capture_output=True, check=True)
    sid = identity.stdout.decode('ascii').strip()
    result = subprocess.run(['icacls.exe', str(path), '/inheritance:r', '/grant:r',
        f'*{sid}:(F)', '*S-1-5-18:(F)', '*S-1-5-32-544:(F)'], capture_output=True)
    if result.returncode:
        raise RuntimeError('模拟账号文件权限设置失败')


# 逐页请求指定资源，合并成后续判断需要的完整列表。
def all_rows(admin, resource):
    result = []
    page = 1
    while True:
        response = admin.request('GET', '/admin/' + resource, params={'page': page, 'size': 100})
        result.extend(response['records'])
        if len(result) >= response['total']:
            return result
        page += 1


# 生成一段示例排课，包含星期、节次、周次和教室。
def slot(day, start, end, room, first_week=1, last_week=16):
    return {'weekday': day, 'startPeriod': start, 'endPeriod': end,
            'startWeek': first_week, 'endWeek': last_week, 'classroom': room}


# 将教学班记录整理为创建或修改接口接收的字段。
def class_payload(row, status=None):
    return {key: (status if key == 'status' and status else row[key])
            for key in ('classCode', 'courseId', 'teacherId', 'semesterId', 'capacity', 'status', 'schedules')}


# 汇总演示数据的数量，供初始化完成后核对。
def summary(admin):
    people = admin.request('GET', '/system/overview')
    enrolled = admin.request('GET', '/admin/enrollments', params={'page': 1, 'size': 1, 'status': 'ENROLLED'})['total']
    dropped = admin.request('GET', '/admin/enrollments', params={'page': 1, 'size': 1, 'status': 'DROPPED'})['total']
    classes = all_rows(admin, 'classes')
    result = {**people, 'activeEnrollments': enrolled, 'droppedEnrollments': dropped,
              'fullClasses': sum(row['selectedCount'] >= row['capacity'] for row in classes),
              'publishedClasses': sum(row['status'] == 'PUBLISHED' for row in classes),
              'closedClasses': sum(row['status'] == 'CLOSED' for row in classes),
              'draftClasses': sum(row['status'] == 'DRAFT' for row in classes)}
    save_json(SUMMARY_FILE, result)
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return result


# 准备演示账号与教学安排，再以不同学生身份建立选退课记录。
def main():
    parser = argparse.ArgumentParser(description='补充学生选课系统模拟数据')
    parser.add_argument('--check', action='store_true', help='只读取当前数量，不写入数据')
    args = parser.parse_args()
    config = json.loads((RELEASE / 'deployment.json').read_text(encoding='utf-8-sig'))
    base_url = f"http://127.0.0.1:{config['webPort']}"
    admin = Client(base_url)
    admin.login('admin', config['samplePassword'])
    state = json.loads(STATE_FILE.read_text(encoding='utf-8')) if STATE_FILE.exists() else {'version': '2026-11-10', 'completed': False}
    # 检查模式或已完成的再次执行只做汇总。初始化通过编号查找现有记录，再增补缺少的数据。
    if args.check or state.get('completed'):
        print('本批模拟数据已完成，当前执行只读数量检查。' if state.get('completed') else '当前执行只读检查。', flush=True)
        summary(admin)
        return

    # 密码只保存到本机私有文件，重复执行沿用原值，不重置任何已有账号。
    if not SETTINGS_FILE.exists():
        save_json(SETTINGS_FILE, {'password': 'Mock2026!' + secrets.token_urlsafe(15)})
    private_file(SETTINGS_FILE)
    password = json.loads(SETTINGS_FILE.read_text(encoding='utf-8'))['password']
    ACCOUNT_FILE.write_text(
        '学生选课系统模拟数据账号\n\n'
        '所有人物资料均为演示模拟数据。\n'
        '学生：SIM2026001 至 SIM2026040；最后两个账号为停用示例。\n'
        '教师：SIMT001 至 SIMT009；SIMT009 为停用示例。\n'
        f'以上新增账号的初始密码：{password}\n\n'
        '推荐学生：SIM2026001（陈晨），推荐教师：SIMT001（陈明）。\n'
        '原有 admin、20260001、T2026001 的密码保持不变。\n'
        '访问地址：' + base_url + '\n', encoding='utf-8-sig')
    private_file(ACCOUNT_FILE)
    save_json(STATE_FILE, state)

    semester = next((row for row in admin.request('GET', '/semesters') if row['name'] == '2026—2027 学年第一学期'), None)
    if semester is None:
        raise RuntimeError('未找到原有演示学期，已停止写入')

    existing_teachers = {row['teacherNo']: row for row in all_rows(admin, 'teachers')}
    teachers = []
    teacher_names = ['陈明', '李静', '王磊', '赵敏', '刘洋', '周琳', '徐浩', '孙悦', '郑文']
    for index, name in enumerate(teacher_names):
        number = f'SIMT{index + 1:03d}'
        row = existing_teachers.get(number)
        if row is None:
            row = admin.request('POST', '/admin/teachers', {'teacherNo': number, 'displayName': name,
                    'department': ['计算机学院', '软件学院', '人工智能学院'][index % 3],
                    'enabled': index != 8, 'initialPassword': password})
        elif row['displayName'] != name:
            raise RuntimeError(f'工号 {number} 已有不同资料，已停止以保护现有数据')
        teachers.append(row)
    print('教师档案已准备完成。', flush=True)

    names = ['陈晨','林子涵','王浩然','李雨桐','张宇轩','刘欣怡','赵一诺','周嘉豪','徐思源','孙梦瑶',
             '吴俊杰','郑诗涵','黄梓轩','何雨欣','高明宇','罗佳怡','梁致远','宋语桐','唐子墨','许若曦',
             '韩奕辰','冯雅琪','邓景行','曹悦宁','彭书航','曾沐晴','肖博文','田可欣','董承泽','袁安琪',
             '潘星宇','于欣然','蒋睿哲','蔡雨萱','杜泽楷','叶舒雅','程昊天','魏语嫣','苏奕帆','丁若琳']
    majors = ['计算机科学与技术', '软件工程', '数据科学与大数据技术', '物联网工程']
    classes = ['计算机2301', '软件2301', '大数据2301', '物联网2301']
    existing_students = {row['studentNo']: row for row in all_rows(admin, 'students')}
    students = []
    for index, name in enumerate(names):
        number = f'SIM2026{index + 1:03d}'
        row = existing_students.get(number)
        if row is None:
            row = admin.request('POST', '/admin/students', {'studentNo': number, 'displayName': name,
                    'major': majors[index // 10], 'className': classes[index // 10], 'enabled': True, 'initialPassword': password})
        elif row['displayName'] != name:
            raise RuntimeError(f'学号 {number} 已有不同资料，已停止以保护现有数据')
        students.append(row)
    print('40 名模拟学生已准备完成。', flush=True)

    course_specs = [('Java程序设计',3,48),('数据结构',4,64),('计算机网络',3,48),('操作系统',3.5,56),
                    ('软件测试技术',2,32),('人工智能导论',2.5,40),('Python数据分析',3,48),('信息安全基础',2,32),
                    ('人机交互设计',2,32),('云计算与容器技术',2.5,40),('物联网应用开发',3,48),('项目管理与协作',2,32)]
    existing_courses = {row['courseCode']: row for row in all_rows(admin, 'courses')}
    courses = []
    for index, (name, credits, hours) in enumerate(course_specs):
        code = f'SIMC{index + 1:03d}'
        row = existing_courses.get(code)
        if row is None:
            row = admin.request('POST', '/admin/courses', {'courseCode': code, 'name': name, 'credits': credits,
                    'hours': hours, 'description': f'{name}模拟课程，用于课程查询、排课、选退课和课表演示。'})
        elif row['name'] != name:
            raise RuntimeError(f'课程编号 {code} 已有不同课程，已停止写入')
        courses.append(row)

    schedules = [[slot(1,3,4,'A-101'),slot(6,5,6,'实验楼101',1,8)],
                 [slot(1,5,6,'A-102'),slot(6,7,8,'实验楼102')],
                 [slot(2,1,2,'A-201'),slot(7,1,2,'实验楼201',1,8)],
                 [slot(2,3,4,'A-202'),slot(7,3,4,'实验楼202',1,12)],
                 [slot(2,5,6,'B-101')],
                 [slot(3,3,4,'B-102'),slot(7,5,6,'实验楼301',1,4)],
                 [slot(3,5,6,'B-201'),slot(7,7,8,'实验楼302',1,8)],
                 [slot(4,1,2,'B-202')], [slot(4,3,4,'C-101')],
                 [slot(4,5,6,'C-102'),slot(7,9,10,'实验楼401',1,4)],
                 [slot(5,3,4,'C-201'),slot(7,11,12,'实验楼402',1,8)], [slot(5,5,6,'C-202')]]
    specifications = []
    for index in range(12):
        specifications.append({'courseId': courses[index]['id'], 'teacherId': teachers[index % 8]['id'],
            'capacity': [5,8,40,35,35,30,35,30,30,30,40,35][index], 'status': 'PUBLISHED', 'schedules': schedules[index]})
    specifications += [
        {'courseId': courses[0]['id'], 'teacherId': teachers[4]['id'], 'capacity': 45, 'status': 'PUBLISHED',
         'schedules': [slot(2,7,8,'D-101'),slot(6,5,6,'实验楼501',9,16)]},
        {'courseId': courses[1]['id'], 'teacherId': teachers[5]['id'], 'capacity': 45, 'status': 'PUBLISHED',
         'schedules': [slot(4,7,8,'D-102'),slot(6,9,10,'实验楼502')]},
        {'courseId': courses[4]['id'], 'teacherId': teachers[6]['id'], 'capacity': 30, 'status': 'PUBLISHED', 'schedules': [slot(6,1,2,'D-201')]},
        {'courseId': courses[9]['id'], 'teacherId': teachers[7]['id'], 'capacity': 30, 'status': 'DRAFT', 'schedules': [slot(6,3,4,'D-202')]},
    ]
    existing_classes = {row['classCode']: row for row in all_rows(admin, 'classes')}
    teaching_classes = []
    for index, spec in enumerate(specifications):
        code = f'SIM-2026A-{index + 1:02d}'
        row = existing_classes.get(code)
        if row is None:
            row = admin.request('POST', '/admin/classes', {'classCode': code, 'semesterId': semester['id'], **spec})
        elif row['courseId'] != spec['courseId'] or row['semesterId'] != semester['id']:
            raise RuntimeError(f'教学班编号 {code} 已被其他开课使用')
        teaching_classes.append(row)
    print('12 门课程、16 个教学班及上课安排已准备完成。', flush=True)

    original_classes = {row['classCode']: row for row in all_rows(admin, 'classes')}
    originals = [original_classes[code] for code in ('CS001-2026-01','CS002-2026-01','CS003-2026-01')]

    # 登录一个演示学生，按目标课程补齐其选退课记录。
    def populate_student(number, student_password, desired, dropped_class=None):
        client = Client(base_url)
        client.login(number, student_password)
        existing = {view['info']['teachingClassId']: view['info'] for view in client.request('GET', '/enrollments')}
        # 选课时按教学班编号识别学生已有的有效选课，后续只提交尚未选择的目标。
        for target in desired:
            if target['id'] not in existing:
                created = client.request('POST', '/enrollments', {'teachingClassId': target['id']})
                existing[target['id']] = created['info']
        if dropped_class is not None:
            previous = existing.get(dropped_class['id'])
            if previous is None:
                previous = client.request('POST', '/enrollments', {'teachingClassId': dropped_class['id']})['info']
            if previous['status'] == 'ENROLLED':
                client.request('DELETE', f"/enrollments/{previous['id']}")
        client.logout()

    for index, student in enumerate(students):
        if not student['enabled']:
            continue
        elective_indices = [(index * 3) % 10 + 2, (index * 3 + 4) % 10 + 2]
        if index % 4 == 0:
            elective_indices.append((index * 3 + 7) % 10 + 2)
        if index < 6 and 4 in elective_indices:
            elective_indices.remove(4)
            elective_indices.append(next(i for i in range(2,12) if i != 4 and i not in elective_indices))
        desired = [teaching_classes[0 if index < 5 else 12], teaching_classes[1 if index < 8 else 13],
                   originals[index % 3]] + [teaching_classes[i] for i in elective_indices]
        if index < 6:
            desired.append(teaching_classes[14])
        desired_course_ids = {row['courseId'] for row in desired}
        dropped = next((teaching_classes[i] for i in range(2,12) if courses[i]['id'] not in desired_course_ids), None) if index < 16 else None
        populate_student(student['studentNo'], password, desired, dropped)
        if (index + 1) % 10 == 0:
            print(f'已完成 {index + 1}/40 名学生的选退课记录。', flush=True)

    populate_student('20260001', config['samplePassword'], originals + [teaching_classes[i] for i in (5,7,8)])
    closed = teaching_classes[14]
    if closed['status'] != 'CLOSED':
        admin.request('PUT', f"/admin/classes/{closed['id']}", class_payload(closed, 'CLOSED'))
    for student in students[-2:]:
        if student['enabled']:
            admin.request('PUT', f"/admin/students/{student['id']}", {key: (False if key == 'enabled' else student[key])
                          for key in ('studentNo','displayName','major','className','enabled')})

    state.update({'completed': True, 'students': [row['studentNo'] for row in students],
                  'teachers': [row['teacherNo'] for row in teachers], 'classes': [row['classCode'] for row in teaching_classes]})
    save_json(STATE_FILE, state)
    print('模拟数据已写入，原有账号与记录保留。', flush=True)
    summary(admin)


if __name__ == '__main__':
    main()
