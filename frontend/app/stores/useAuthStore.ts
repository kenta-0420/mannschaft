import { defineStore } from 'pinia'

interface AuthUser {
  id: number
  email: string
  displayName: string
  profileImageUrl: string | null
  systemRole?: string
}

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: AuthUser | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: null,
    refreshToken: null,
    user: null,
  }),

  getters: {
    isAuthenticated: (state): boolean => !!state.accessToken,
    currentUser: (state): AuthUser | null => state.user,
    isSystemAdmin: (state): boolean => state.user?.systemRole === 'SYSTEM_ADMIN',
  },

  actions: {
    setTokens(accessToken: string, refreshToken: string) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      if (import.meta.client) {
        localStorage.setItem('accessToken', accessToken)
        localStorage.setItem('refreshToken', refreshToken)
      }
    },

    setUser(user: AuthUser) {
      this.user = user
    },

    loadFromStorage() {
      if (import.meta.client) {
        this.accessToken = localStorage.getItem('accessToken')
        this.refreshToken = localStorage.getItem('refreshToken')
      }
    },

    logout() {
      this.accessToken = null
      this.refreshToken = null
      this.user = null
      if (import.meta.client) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
      }
      navigateTo('/login')
    },

    async serverLogout() {
      try {
        const api = useApi()
        await api('/api/v1/auth/logout', {
          method: 'POST',
          body: { refreshToken: this.refreshToken },
        })
      } catch {
        // ignore errors - we're logging out anyway
      } finally {
        this.logout()
      }
    },
  },
})
