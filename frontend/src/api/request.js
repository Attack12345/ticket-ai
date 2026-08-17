import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import router from '../router'

// axios 封装（DEV_DOC §7.2）：token 注入 + 401 自动刷新重放
const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000
})

request.interceptors.request.use(config => {
  const userStore = useUserStore()
  if (userStore.accessToken) {
    config.headers.Authorization = `Bearer ${userStore.accessToken}`
  }
  return config
})

let refreshing = false
let waiters = []

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message))
  },
  async error => {
    const { response, config } = error
    if (response && response.status === 401 && !config._retried) {
      const userStore = useUserStore()
      // 登录/刷新接口自身 401 不重试
      if (config.url.includes('/auth/')) {
        userStore.logout()
        router.push('/login')
        return Promise.reject(error)
      }
      config._retried = true
      // 并发 401 只刷新一次
      if (!refreshing) {
        refreshing = true
        try {
          const data = await axios.post('/api/v1/auth/refresh', {
            refreshToken: userStore.refreshToken
          })
          userStore.setTokens(data.data.accessToken, data.data.refreshToken)
          waiters.forEach(w => w(true))
          waiters = []
        } catch (e) {
          waiters.forEach(w => w(false))
          waiters = []
          userStore.logout()
          router.push('/login')
          return Promise.reject(e)
        } finally {
          refreshing = false
        }
      }
      const ok = await new Promise(resolve => waiters.push(resolve))
      if (ok) {
        return request(config)
      }
      return Promise.reject(error)
    }
    ElMessage.error(response?.data?.message || '网络异常，请稍后重试')
    return Promise.reject(error)
  }
)

export default request
