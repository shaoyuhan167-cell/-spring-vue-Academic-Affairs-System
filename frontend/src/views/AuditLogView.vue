<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../api/modules'
import { dateTime } from '../utils/academic'

const actions = {
  LOCAL_SWITCH: '本机登录或切换身份',
  PASSWORD_CHANGE: '修改密码',
  PASSWORD_RESET: '重置密码',
  ACCOUNT_UPDATE: '更新账号状态',
  CLASS_DELETE: '删除教学班',
  ENROLL_DROP: '退选课程',
  GRADE_SAVE: '保存成绩',
  GRADE_PUBLISH: '发布成绩',
  GRADE_WITHDRAW: '撤回成绩',
}
const roles = { ADMIN: '管理员', STUDENT: '学生', TEACHER: '教师' }
const targets = { SELF: '本人账号', ACCOUNT: '账号', STUDENT: '学生', TEACHER: '教师', TEACHING_CLASS: '教学班', ENROLLMENT: '选课记录' }
const action = ref('')
const rows = ref([])
const loading = ref(false)
const error = ref('')

// 使用现有审计接口按操作类型查询最近的记录。
async function load() {
  loading.value = true
  error.value = ''
  rows.value = []
  try {
    rows.value = await adminApi.list('audit', { action: action.value || undefined })
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-heading">
      <div><h1>审计日志</h1><p>显示符合筛选条件的最近 50 条操作记录。</p></div>
    </div>
    <section class="panel">
      <div class="table-toolbar">
        <el-select v-model="action" aria-label="操作类型" placeholder="全部操作" clearable
          class="heading-select" :disabled="loading" @change="load">
          <el-option v-for="(label, code) in actions" :key="code" :label="label" :value="code" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <el-table v-else v-loading="loading" :data="rows" row-key="id" empty-text="暂无操作记录">
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="actorName" label="操作人" min-width="115">
          <template #default="{ row }">{{ row.actorName }} <span class="muted">{{ roles[row.actorRole] || row.actorRole }}</span></template>
        </el-table-column>
        <el-table-column label="操作" min-width="140">
          <template #default="{ row }">{{ actions[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column label="操作对象" min-width="120">
          <template #default="{ row }">
            {{ targets[row.targetType] || row.targetType }}
            <span v-if="row.targetId != null">#{{ row.targetId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="230" />
      </el-table>
    </section>
  </div>
</template>
