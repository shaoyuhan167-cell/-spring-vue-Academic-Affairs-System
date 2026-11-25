// 配置 API 请求，集中处理登录凭据、CSRF 令牌和接口错误。
import axios from 'axios'

// 用同源的 /api 作为前缀，开发与正式部署都能使用同一套请求地址。会话 Cookie 由浏览器随请求发送。
const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
  withCredentials: true,
})

let csrfToken = null
let csrfRequest = null

// 清掉缓存令牌，让下次写请求使用当前会话重新获取。
export function clearCsrfToken() {
  csrfToken = null
}

// 优先复用当前已缓存的令牌，没有时再向后端获取。
async function getCsrfToken() {
  if (csrfToken) return csrfToken
  // 同时发出多个修改请求时共享一次令牌请求，避免各自重复获取。
  if (!csrfRequest) {
    csrfRequest = http.get('/auth/csrf').then((data) => {
      csrfToken = data
      return data
    }).finally(() => { csrfRequest = null })
  }
  return csrfRequest
}

// 修改类请求统一添加 CSRF 请求头，查阅类请求直接发送。
http.interceptors.request.use(async (config) => {
  if (!['get', 'head', 'options'].includes(config.method?.toLowerCase())) {
    const token = await getCsrfToken()
    config.headers[token.headerName] = token.token
  }
  return config
})

// 统一解包 API 响应，处理会话失效并提取可展示的错误提示。
http.interceptors.response.use(
  (response) => {
    // 下载接口返回文件内容，可直接交给浏览器保存。
    if (response.config.responseType === 'blob') return response.data
    if (response.data.code !== 0) {
      return Promise.reject(new Error(response.data.message || '请求失败'))
    }
    return response.data.data
  },
  async (error) => {
    // 业务接口返回未登录时通知路由处理。登录接口自己的 401 仍交给登录表单显示，不触发会话跳转。
    if (error.response?.status === 401 && !error.config?.url?.startsWith('/auth/')) {
      window.dispatchEvent(new Event('session-expired'))
    }
    if (error.response?.status === 403) clearCsrfToken()
    // 下载失败也可能以文件格式收到后端的 JSON 错误。先尝试还原错误消息，再提供统一失败提示。
    if (error.response?.data instanceof Blob) {
      try { error.response.data = JSON.parse(await error.response.data.text()) } catch { /* 保留统一错误提示。 */ }
    }
    return Promise.reject(new Error(error.response?.data?.message || '无法连接服务，请检查后端是否已启动'))
  },
)

export default http
