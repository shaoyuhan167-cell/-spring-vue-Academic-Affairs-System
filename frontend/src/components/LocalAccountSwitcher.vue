<script setup>
// 本机免密登录启用时，展示可切换的账号并提交切换请求。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '../api/modules'
import { authState, switchLocalAccount } from '../stores/auth'

defineProps({ compact: Boolean })

const roles = [
  { value: 'STUDENT', label: '学生' },
  { value: 'TEACHER', label: '教师' },
  { value: 'ADMIN', label: '管理员' },
]
const router = useRouter()
const available = ref(false)
const open = ref(false)
const loading = ref(false)
const switching = ref('')
const error = ref('')
const accounts = ref([])
const role = ref('STUDENT')
const keyword = ref('')
// 按当前搜索词筛选账号，便于查找要切换的身份。
// 候选账号按选中的角色和搜索词筛选。输入只影响页面显示，真正切换仍由后端校验。
const filteredAccounts = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return accounts.value.filter((account) => account.role === role.value
    && [account.username, account.displayName].some((value) => value.toLowerCase().includes(search)))
})

// 请求本机切换的开关状态和可选账号。
async function loadAccounts() {
  loading.value = true
  error.value = ''
  try {
    // 以服务器返回的 enabled 为准，因此部署设置关闭、或以非本机地址访问时都可以隐藏入口。
    const options = await authApi.localAccounts()
    available.value = options.enabled
    accounts.value = options.accounts
    if (!available.value && open.value) error.value = '当前访问方式未启用本机免密登录'
  } catch (exception) {
    if (open.value) error.value = exception.message
  } finally {
    loading.value = false
  }
}

// 打开选择器时清空搜索，先显示完整账号列表。
function openPicker() {
  role.value = authState.user?.role ?? 'STUDENT'
  keyword.value = ''
  open.value = true
  loadAccounts()
}

// 选中不同账号时发起切换，让页面重新加载新身份的数据。
async function selectAccount(account) {
  // 当前账号无需重复切换。正在提交时先拦住第二次点击，避免两个身份请求交错完成。
  if (switching.value || account.username === authState.user?.username) return
  switching.value = account.username
  error.value = ''
  try {
    // 先处理当前页面的离开确认，再更换会话，避免丢弃未保存的编辑。
    if (authState.user && router.currentRoute.value.path !== '/dashboard') {
      await router.replace('/dashboard')
      if (router.currentRoute.value.path !== '/dashboard') return
    }
    await switchLocalAccount(account.username)
  } catch (exception) {
    error.value = exception.message
  } finally {
    switching.value = ''
  }
}

// 组件出现时先查询能否显示本机切换入口。
onMounted(loadAccounts)
</script>

<!-- 切换可用时显示入口，弹窗内提供搜索和身份选择。 -->
<template>
  <el-button v-if="available" type="primary" :text="compact" :plain="!compact"
    :class="{ 'local-login-button': !compact }" @click="openPicker">
    {{ compact ? '切换身份' : '选择用户免密登录' }}
  </el-button>
  <el-dialog v-model="open" title="选择登录用户" width="560px" append-to-body
    :close-on-click-modal="!switching" :close-on-press-escape="!switching" :show-close="!switching">
    <div class="account-picker">
      <div class="account-picker-roles" role="group" aria-label="选择身份">
        <el-button v-for="item in roles" :key="item.value" :type="role === item.value ? 'primary' : 'default'"
          :aria-pressed="role === item.value" :disabled="!!switching" @click="role = item.value; keyword = ''">
          {{ item.label }}
        </el-button>
      </div>
      <el-input v-model="keyword" placeholder="搜索姓名、学号、工号或账号" aria-label="搜索用户"
        :disabled="!!switching" clearable />
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div v-loading="loading" class="account-picker-results">
        <ul v-if="filteredAccounts.length" class="account-picker-list" aria-label="可登录用户">
          <li v-for="account in filteredAccounts" :key="account.id">
            <button type="button" class="account-picker-user"
              :disabled="loading || !!switching || account.username === authState.user?.username"
              @click="selectAccount(account)">
              <span class="account-picker-avatar" aria-hidden="true">{{ account.displayName.slice(0, 1) }}</span>
              <span class="account-picker-name"><strong>{{ account.displayName }}</strong><small>{{ account.username }}</small></span>
              <span class="account-picker-action">
                {{ switching === account.username ? '登录中' : account.username === authState.user?.username ? '当前用户' : '登录' }}
              </span>
            </button>
          </li>
        </ul>
        <el-empty v-else-if="!loading" description="没有匹配的用户" :image-size="68" />
      </div>
    </div>
  </el-dialog>
</template>

<!-- 账号切换按钮和选择列表使用组件内样式，避免影响其他表单。 -->
<!-- 账号选择弹窗既可在登录页使用，也可嵌入导航。局部样式只管理该组件内的角色选项和滚动列表。 -->
<style scoped>
.local-login-button { width: 100%; margin-bottom: 22px; }
.account-picker { display: grid; gap: 14px; }
.account-picker-roles { display: flex; gap: 8px; }
.account-picker-roles .el-button { flex: 1; margin: 0; }
.account-picker-results { max-height: min(400px, 48vh); min-height: 110px; overflow-y: auto; }
.account-picker-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.account-picker-user { display: flex; align-items: center; gap: 12px; width: 100%; padding: 10px 12px; border: 1px solid #dce5da; border-radius: 9px; background: #fbfcfa; color: #26362e; text-align: left; font: inherit; cursor: pointer; }
.account-picker-user:hover:not(:disabled), .account-picker-user:focus-visible { border-color: var(--el-color-primary); background: #edf4eb; }
.account-picker-user:disabled { cursor: default; }
.account-picker-avatar { display: grid; place-items: center; flex: 0 0 36px; height: 36px; border-radius: 10px; background: #e4eee1; font-weight: 600; }
.account-picker-name { display: grid; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
.account-picker-name small { color: #5c6a60; }
.account-picker-action { margin-left: auto; flex-shrink: 0; color: #435b4a; font-size: 12px; }
</style>
