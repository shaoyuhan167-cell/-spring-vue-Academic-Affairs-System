"""通过本机 API 补充扩展功能的模拟数据，不改账号密码或原选课记录。"""
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SETTINGS = json.loads((ROOT / 'release/windows/deployment.json').read_text(encoding='utf-8-sig'))
BASE = f"http://127.0.0.1:{SETTINGS['webPort']}/api"
CLIENT = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


# 通过保留会话的客户端调用 API，并在写入前取得防伪令牌。
def request(path, method='GET', body=None):
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if method != 'GET':
        csrf = request('/auth/csrf')
        headers[csrf['headerName']] = csrf['token']
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers=headers, method=method)
    with CLIENT.open(req, timeout=30) as response:
        result = json.load(response)
    if result['code'] != 0:
        raise RuntimeError(result['message'])
    return result['data']


# 补充成绩、公告和资料，准备可用于前端演示的学习数据。
def run():
    request('/auth/login', 'POST', {'username': 'admin', 'password': SETTINGS['samplePassword']})
    notices = [
        ('【演示】欢迎使用新版选课系统', 'ALL', True, '成绩中心、个人资料、公告通知和 CSV 导出已开放。\n此公告为实习演示数据，不代表学校正式通知。'),
        ('【演示】学生成绩查询说明', 'STUDENT', False, '在“成绩中心”查看教师已发布的成绩。SIM2026001～SIM2026006 已有一门软件测试技术的模拟成绩。\n所有分数均为虚构演示数据。'),
        ('【演示】教师成绩录入流程', 'TEACHER', False, '在“成绩中心”选择本人教学班，录入平时与期末成绩，保存全班草稿后发布。已发布成绩需先撤回再修改。\nSIMT007 可查看软件测试技术班的已发布模拟成绩。'),
    ]
    created = 0
    # 用样例公告标题判断是否已经创建，重复执行不会再发布同一条演示公告。
    for title, audience, pinned, content in notices:
        existing = request('/admin/announcements?size=100&keyword=' + urllib.parse.quote(title))
        if any(row['title'] == title for row in existing['records']):
            continue
        request('/admin/announcements', 'POST', dict(title=title, content=content, audience=audience, status='PUBLISHED', pinned=pinned, visibleFrom=None, expiresAt=None, revision=0))
        created += 1
    classes = request('/grades/classes')
    target = next((row for row in classes if row['classCode'] == 'SIM-2026A-15'), None)
    seeded = 0
    if target:
        book = request(f"/grades/classes/{target['id']}")
        # 仅在存在学生且全班还没有成绩记录时写入演示成绩，已有录入可以继续保留。
        if book['rows'] and all(row['revision'] == 0 for row in book['rows']):
            scores = [(85, 90), (72, 78), (92, 95), (65, 70), (58, 55), (88, 84)]
            entries = [dict(enrollmentId=row['enrollmentId'], usualScore=scores[index % len(scores)][0], examScore=scores[index % len(scores)][1], remark='虚构模拟成绩，仅用于实习演示', revision=0) for index, row in enumerate(book['rows'])]
            book = request(f"/grades/classes/{target['id']}", 'PUT', dict(usualWeight=30, entries=entries))
            entries = [dict(enrollmentId=row['enrollmentId'], usualScore=row['usualScore'], examScore=row['examScore'], remark=row['remark'], revision=row['revision']) for row in book['rows']]
            request(f"/grades/classes/{target['id']}/publish", 'POST', dict(usualWeight=30, entries=entries))
            seeded = len(entries)
    manifest = dict(createdNotices=created, createdGrades=seeded, teachingClass='SIM-2026A-15', existingDataPreserved=True)
    (ROOT / '.runtime/learning-demo-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    run()
