// 管理前端的当前身份，并负责刷新后的会话恢复和本机账号切换。
import { reactive } from 'vue'
import { authApi } from '../api/modules'
import { clearCsrfToken } from '../api/http'

// 账号信息只保存在内存；刷新时通过服务端 Session 恢复身份。
export const authState = reactive({ user: null, initialized: false })
let restoring = null
// 本机切换账号后让同源的其他标签页重新加载，一起使用新会话。
const sessionChanges = typeof BroadcastChannel === 'function' ? new BroadcastChannel('course-selection-session') : null
sessionChanges?.addEventListener('message', (event) => {
  if (event.data === 'identity-changed') window.location.reload()
})

// 页面刷新后向后端确认当前身份，并记录初始化结果。
export async function restoreSession() {
  if (authState.initialized) return
  // 多个调用方同时恢复会话时，共用一次查询当前账号的请求。
  if (!restoring) {
    restoring = authApi.me()
      .then((user) => { authState.user = user })
      .catch(() => { authState.user = null })
      // 无论查询到账号还是以匿名状态结束，都标记本次会话已确认。后续路由切换可以直接使用当前结果。
      .finally(() => { authState.initialized = true; restoring = null })
  }
  await restoring
}

// 按表单凭据登录，保存返回的身份并刷新安全令牌缓存。
export async function login(credentials) {
  authState.user = await authApi.login(credentials)
  authState.initialized = true
  clearCsrfToken()
}

// 免密切换本机账号，通知其他标签页一同重新加载。
export async function switchLocalAccount(username) {
  await authApi.localLogin(username)
  // 浏览器同站点的标签页共享会话 Cookie。广播通知其他标签页清掉旧页面状态，按新账号重新读取权限。
  sessionChanges?.postMessage('identity-changed')
  // 重新加载工作台，从新会话读取身份和业务数据。
  window.location.replace('/dashboard')
}

// 清除前端缓存的当前账号和令牌。
export function clearSession() {
  authState.user = null
  authState.initialized = true
  clearCsrfToken()
}

// 要求后端销毁登录会话，再清理前端的身份信息。
export async function logout() {
  await authApi.logout()
  clearSession()
}
