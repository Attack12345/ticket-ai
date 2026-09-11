import { defineStore } from 'pinia'
import axios from 'axios'

// 用户会话（DEV_DOC §7.2：token + 权限码）
export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    username: localStorage.getItem('username') || '',
    permissions: JSON.parse(localStorage.getItem('permissions') || '[]')
  }),
  actions: {
    setTokens(accessToken, refreshToken) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', refreshToken)
    },
    setUser(username, permissions) {
      this.username = username
      this.permissions = permissions || []
      localStorage.setItem('username', username)
      localStorage.setItem('permissions', JSON.stringify(this.permissions || []))
    },
    logout() {
      const accessToken = this.accessToken
      const refreshToken = this.refreshToken
      this.accessToken = ''
      this.refreshToken = ''
      this.username = ''
      this.permissions = []
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('username')
      localStorage.removeItem('permissions')
      // P0-7：尽力通知服务端吊销令牌（不入 request 拦截器，避免循环依赖；
      // 失败不阻断本地登出，过期/失效由服务端幂等忽略）
      if (refreshToken) {
        axios.post('/api/v1/auth/logout', { refreshToken }, {
          headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}
        }).catch(() => {})
      }
    },
    hasPermission(code) {
      return this.permissions.includes(code)
    }
  }
})
