import { defineStore } from 'pinia'

interface AuthUser {
  id: number
  email: string
  fullName: string
  profileImageUrl: string | null
  systemRole?: string
  /** IANA タイムゾーン識別子（例: Asia/Tokyo）。未設定時は 'Asia/Tokyo' をデフォルトとして使用する。 */
  timezone?: string
  /** UI 表示ロケール（例: 'ja', 'en'）。未設定時はアプリのデフォルトロケールを使用する。 */
  locale?: string
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

      // 非機密のタイムスタンプ（access_token の有効期限）のみ localStorage に保存する。
      // トークン本体は in-memory のまま保持し、ここでは exp（秒）→ ミリ秒のみを残す。
      // 起動プラグイン（auth.client）がリロード時に「Cookie 失効済みか」を判定し、
      // 失効時だけ先回りリフレッシュするために使う。
      if (import.meta.client) {
        try {
          const payloadPart = accessToken.split('.')[1]
          if (payloadPart) {
            // JWT payload は base64url エンコードのため base64 に変換してからデコードする。
            const base64 = payloadPart.replace(/-/g, '+').replace(/_/g, '/')
            const payload = JSON.parse(atob(base64)) as { exp?: number }
            if (typeof payload.exp === 'number') {
              localStorage.setItem('tokenExpiresAt', String(payload.exp * 1000))
            }
          }
        } catch {
          // JWT デコード失敗・exp 欠落時は保存をスキップ（例外は投げない）。
          // tokenExpiresAt が無ければプラグイン側は「期限不明＝失効扱い」で先回りリフレッシュする。
        }
      }

      // 先回り（proactive）リフレッシュタイマーの武装は、ここ（setTokens）では行わない。
      //
      // 【理由】setTokens はタイマー起点のリフレッシュ経路（armProactiveRefresh の fire →
      // performTokenRefresh → 内部で setTokens）からも呼ばれる。その経路では setTokens は
      // setTimeout の非同期コンテキスト（await 後）で実行されるため、ここで useRuntimeConfig() /
      // useAuthStore() を呼ぶのは「composable は同期 Nuxt コンテキストでのみ呼ぶ」不変条件に反する
      // （memory feedback_nuxt_plugin_no_setup_composables）。実機検証では現状 Nuxt3 の client
      // フォールバックで例外は出なかったが、Nuxt 内部実装の変更で将来落ちうる潜在リスクであり、
      // かつ fire 側が成功時に captured config で再武装するため setTokens 内の arm は冗長な二重武装
      // だった。よって arm は「必ず同期コンテキストである呼び出し元」に寄せる:
      //   - ログイン成功各所（login.vue / 2fa-verify.vue / 2fa-recovery.vue / OAuth コールバック）
      //   - auth.client プラグイン起動時（認証済みなら）
      // リフレッシュ成功後の再武装は useApi.ts の fire コールバックが captured config で担う。
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
      // close() を先に呼ぶことで、Dexie の onblocked（他接続が残っている状態の削除待ち）を回避し
      // delete() を即時完了させる。
      try {
        const { offlineDb } = await import('~/composables/useOfflineDb')
        try {
          offlineDb.close()
        } catch {
          // close 失敗は無視して delete を続行
        }
        await offlineDb.delete()
      } catch {
        // DB 削除失敗は処理継続
      }

      // F18 ウォレット: オフラインキャッシュ DB をクリア（鍵ごと削除）
      // 設計書 §7.4「ログアウト時に鍵を破棄 + IndexedDB の全レコードを削除」
      // walletOfflineStore は各操作で open/close するため close() 事前呼び出し不要。
      try {
        const { deleteWalletOfflineDb } = await import('~/utils/walletOfflineStore')
        await deleteWalletOfflineDb()
      } catch {
        // DB 削除失敗は処理継続
      }
    },

    /**
     * ログイン直後にアカウント設定（外観・ナビ）をサーバーから同期する。
     *
     * 【呼び出しタイミング】
     * 実ログイン成立後（login.vue・2fa-verify.vue・OAuth コールバック）にのみ呼ぶこと。
     * プロフィール更新（locale/avatar 変更等）での setUser 呼び出し時には呼ばない。
     *
     * 【設計方針】
     * 外観・ナビ設定の同期失敗でログイン遷移をブロックしない（fire-and-forget）。
     * BEに保存済み設定が新ブラウザ（シークレット等）でも反映されるよう、
     * ログイン直後に localStorage/cookie へ永続化し DOM に適用する。
     *
     * loadFromServer() は成功時に localStorage/cookie へ永続化・DOM 適用まで完了するため、
     * 呼び出し元で追加処理は不要。
     */
    syncAccountSettings() {
      if (!import.meta.client) return
      // ログイン遷移をブロックしないよう void で fire-and-forget する。
      // 失敗は握りつぶす（表示設定の同期失敗でログインを止めない設計判断）。
      void Promise.all([
        useAppearanceStore().loadFromServer(),
        useNavSettingsStore().loadFromServer(),
      ]).catch((err) => {
        console.error('[syncAccountSettings] 設定同期失敗:', err)
      })
    },

    async setUser(user: AuthUser) {
      // 処理順: 旧ユーザー ID を先読み → state/localStorage を即時設定 → 非同期破棄を後置。
      // state 先行により、直後の isSystemAdmin 等の getter が新ユーザーの値で正しく評価される。
      // キャッシュ破棄はその後に実行されるため、破棄中の Dexie 書き込み競合も発生しない。
      let needsCacheClear = false
      if (import.meta.client) {
        try {
          const savedRaw = localStorage.getItem('currentUser')
          if (savedRaw) {
            const savedUser = JSON.parse(savedRaw) as AuthUser
            if (savedUser.id !== user.id) {
              // 別ユーザーへの切替: 旧ユーザーの個人データキャッシュを後で破棄する
              needsCacheClear = true
            }
          }
        } catch {
          // JSON パースエラー等は無視して続行
        }
      }

      // state と localStorage を同期的に即時設定（直後の getter が新ユーザーで評価されるよう先行）
      this.user = user
      if (import.meta.client) {
        localStorage.setItem('currentUser', JSON.stringify(user))
      }

      // キャッシュ破棄は state 設定後に行う（state 先行・破棄後置）
      if (needsCacheClear) {
        await this.clearUserCaches()
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

    /**
     * ログアウト処理。state/localStorage を即時クリアして /login へ遷移し、
     * PWA キャッシュ（Cache Storage + IndexedDB）の重い削除は遷移と並走させる。
     *
     * 【設計方針】
     * - state クリア・localStorage クリア・navigateTo は同期的に先行する（遷移の即時化）。
     * - Cache Storage 全削除・IndexedDB 削除は Promise を起動するが、await で遷移をブロックしない。
     * - SPA 遷移では JS コンテキストが継続するため、navigateTo 後もクリーンアップ Promise は
     *   走り続けて必ず完了する（fire-and-forget ではなく「遷移と並走」）。
     *
     * @param options.reason - ログイン画面に表示する遷移理由。
     *   - 'session_expired': セッション失効（refresh_token 無効。他タブやメール変更・退会後など）
     *   - 'password_changed': パスワード変更後の能動的ログアウト（password.vue から呼ばれる）
     *   省略時は従来どおり /login へ遷移（後方互換）。
     */
    async logout(options?: { reason?: string }) {
      // ① 即時処理: in-memory state と localStorage を同期的にクリアする。
      //   これ以降 isAuthenticated === false になり、ガードが /login にリダイレクトできる。
      this.accessToken = null
      this.refreshToken = null
      this.user = null
      // 武装中の先回りリフレッシュタイマーを解除する（AC-3）。以後リフレッシュは発火しない。
      disarmProactiveRefresh()
      if (import.meta.client) {
        // accessToken・refreshToken の localStorage エントリは廃止済みだが、
        // 移行前の古いデータが残っている場合のクリーンアップとして削除する。
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('currentUser')
        localStorage.removeItem('tokenExpiresAt')
      }
      useChatTabsStore().clearAll()

      // ② 重いクリーンアップ（Cache Storage + IndexedDB）を並走で起動する。
      //   navigateTo より前に起動することで「必ず走る」ことを保証しつつ、
      //   await しないことで遷移をブロックしない。
      //   SPA 遷移では JS コンテキストが継続するため、遷移後もこの Promise は完了する。
      const cleanupPromise = import.meta.client
        ? (async () => {
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
          })()
        : Promise.resolve()

      // ③ 遷移を即時実行（クリーンアップ完了を待たない）。
      const loginPath = options?.reason ? `/login?reason=${options.reason}` : '/login'
      navigateTo(loginPath)

      // ④ 遷移後もクリーンアップ Promise が完了するまで呼び出し元が await できるよう返す。
      //   通常の呼び出し元は await logout() で完了を待てるが、遷移は ③ で先行済みのため
      //   UX 上の遅延は発生しない。
      await cleanupPromise
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
