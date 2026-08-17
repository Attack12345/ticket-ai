import { defineStore } from 'pinia'

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
      this.accessToken = ''
      this.refreshToken = ''
      this.username = ''
      this.permissions = []
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('username')
      localStorage.removeItem('permissions')
    },
    hasPermission(code) {
      return this.permissions.includes(code)
    }
  }
})
