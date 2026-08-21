import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 延迟注入 router，避免循环依赖
let _router = null
export const setRouter = (r) => { _router = r }

// 请求拦截：自动注入 JWT（Authorization: Bearer <token>）
request.interceptors.request.use(config => {
  const token = localStorage.getItem('eval-token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：
//   200 + code===200      → 业务成功，直接返回 res（含 data）
//   200 + code!==200      → 业务失败，toast 错误
//   HTTP 401             → 登录失效/未登录，清理本地登录态并跳转登录页
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    const status = error.response?.status
    if (status === 401) {
      const loggedOut = localStorage.getItem('eval-token')
      localStorage.removeItem('eval-token')
      localStorage.removeItem('eval-user')
      if (_router && _router.currentRoute.value.path !== '/login') {
        if (loggedOut) {
          ElMessage.warning('登录已过期，请重新登录')
        }
        const redirect = encodeURIComponent(_router.currentRoute.value.fullPath)
        _router.push(`/login?redirect=${redirect}`)
      } else if (!error.response?.config?.url?.includes('/auth/login')) {
        ElMessage.error(error.response?.data?.message || '用户名或密码错误')
      }
      return Promise.reject(error)
    }
    ElMessage.error(error.response?.data?.message || error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request