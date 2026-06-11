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

    /**
     * PWA キャッシュ（Cache Storage + IndexedDB）を破棄する共通処理。
     * ログアウト時とユーザー切替時の両方から呼ばれる。
     * 個人データが Service Worker キャッシュに残存するリスクを根治する。
     */
    async clearUserCaches(): Promise<void> {
      if (!import.meta.client) return

      // PWA: api-cache を削除（認証付き API レスポンスの情報漏洩防止）
      if ('caches' in window) {
        try {
          await caches.delete('api-cache')
        } catch {
          // キャッシュ削除失敗は処理継続（ログアウト/切替自体をブロックしない）
        }
      }

      // PWA: IndexedDB (Dexie) のオフライン DB をクリア
      try {
        const { offlineDb } = await import('~/composables/useOfflineDb')
        await offlineDb.delete()
      } catch {
        // DB 削除失敗は処理継続
      }

      // F18 ウォレット: オフラインキャッシュ DB をクリア（鍵ごと削除）
      // 設計書 §7.4「ログアウト時に鍵を破棄 + IndexedDB の全レコードを削除」
      try {
        const { deleteWalletOfflineDb } = await import('~/utils/walletOfflineStore')
        await deleteWalletOfflineDb()
      } catch {
        // DB 削除失敗は処理継続
      }
    },

    async setUser(user: AuthUser) {
      // ユーザー切替を検知: localStorage に保存された旧ユーザーの id と
      // 新ユーザーの id が異なる場合のみ api-cache を破棄する。
      // 同一ユーザーのページリロード（復元）では破棄しない
      // → オフライン機能が毎回のリロードで殺されることを防ぐ。
      if (import.meta.client) {
        try {
          const savedRaw = localStorage.getItem('currentUser')
          if (savedRaw) {
            const savedUser = JSON.parse(savedRaw) as AuthUser
            if (savedUser.id !== user.id) {
              // 別ユーザーへの切替: 旧ユーザーの個人データキャッシュを破棄
              await this.clearUserCaches()
            }
          }
        } catch {
          // JSON パースエラー等は無視して続行
        }
      }

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

        // PWA: Cache Storage を全クリア（api-cache + その他全エントリ）
        // api-cache の破棄は clearUserCaches でも行われるが、
        // ログアウト時はあらゆるキャッシュを完全消去するため全削除する。
        if ('caches' in window) {
          try {
            const names = await caches.keys()
            await Promise.all(names.map((name) => caches.delete(name)))
          } catch {
            // キャッシュ削除失敗は握りつぶす（ログアウト自体は継続）
          }
        }

        // PWA: IndexedDB (Dexie) + F18 ウォレット DB をクリア（clearUserCaches と共通化）
        await this.clearUserCaches()
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
        await this.logout()
      }
    },
  },
})
