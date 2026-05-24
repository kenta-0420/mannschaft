import { defineStore } from 'pinia'

interface AuthUser {
  id: number
  email: string
  fullName: string
  profileImageUrl: string | null
  systemRole?: string
  /** IANA タイムゾーン識別子（例: Asia/Tokyo）。未設定時は 'Asia/Tokyo' をデフォルトとして使用する。 */
  timezone?: string
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
    // accessToken は in-memory のみで管理（localStorage 非使用）。
    // ページリロード後はトークンがメモリから消えるが、user オブジェクトは localStorage に残るため
    // user の有無で認証状態を判定する。未認証の API 呼び出しは 401 → リフレッシュフローで自動復旧する。
    isAuthenticated: (state): boolean => !!state.user,
    currentUser: (state): AuthUser | null => state.user,
    isSystemAdmin: (state): boolean => state.user?.systemRole === 'SYSTEM_ADMIN',
  },

  actions: {
    setTokens(accessToken: string, refreshToken: string) {
      // トークンは Pinia in-memory のみで保持する。
      // localStorage への保存は XSS でトークンを盗まれる脆弱性の原因となるため廃止。
      // accessToken は HttpOnly Cookie でも管理されており、Cookie はブラウザが自動送信する。
      this.accessToken = accessToken
      this.refreshToken = refreshToken
    },

    setUser(user: AuthUser) {
      this.user = user
      if (import.meta.client) {
        localStorage.setItem('currentUser', JSON.stringify(user))
      }
    },

    loadFromStorage() {
      // accessToken・refreshToken は localStorage に保存しなくなったため読み込まない。
      // user 情報のみ復元し、isAuthenticated の判定に使用する。
      // アクセストークンは HttpOnly Cookie から自動送信されるため、ページリロード後も API は正常動作する。
      if (import.meta.client) {
        const savedUser = localStorage.getItem('currentUser')
        if (savedUser) {
          try {
            this.user = JSON.parse(savedUser)
          } catch {
            // ignore parse errors
          }
        }
      }
    },

    async logout() {
      this.accessToken = null
      this.refreshToken = null
      this.user = null
      if (import.meta.client) {
        // accessToken・refreshToken の localStorage エントリは廃止済みだが、
        // 移行前の古いデータが残っている場合のクリーンアップとして削除する。
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('currentUser')

        // PWA: Cache Storage をクリア
        if ('caches' in window) {
          try {
            const names = await caches.keys()
            await Promise.all(names.map((name) => caches.delete(name)))
          } catch {
            // キャッシュ削除失敗は握りつぶす（ログアウト自体は継続）
          }
        }

        // PWA: IndexedDB (Dexie) のオフライン DB をクリア
        try {
          const { offlineDb } = await import('~/composables/useOfflineDb')
          await offlineDb.delete()
        } catch {
          // DB 削除失敗は握りつぶす
        }

        // F18 ウォレット: オフラインキャッシュ DB をクリア（鍵ごと削除）
        // 設計書 §7.4「ログアウト時に鍵を破棄 + IndexedDB の全レコードを削除」
        try {
          const { deleteWalletOfflineDb } = await import('~/utils/walletOfflineStore')
          await deleteWalletOfflineDb()
        } catch {
          // DB 削除失敗は握りつぶす（ログアウト自体は継続）
        }
      }
      useChatTabsStore().clearAll()
      navigateTo('/login')
    },

    async serverLogout() {
      try {
        const api = useApi()
        await api('/api/v1/auth/logout', {
          method: 'POST',
        })
      } catch {
        // ignore errors - we're logging out anyway
      } finally {
        this.logout()
      }
    },
  },
})
