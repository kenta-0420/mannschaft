import { defineStore } from 'pinia'
import type { components } from '~/types/generated'

// 生成型エイリアス
type AppearanceResponse = components['schemas']['AppearanceResponse']
type UpdateAppearanceRequest = components['schemas']['UpdateAppearanceRequest']

type ThemeMode = 'LIGHT' | 'DARK'

interface AppearanceState {
  theme: ThemeMode
  bgColor: string
  darkBgColor: string
  seasonalThemeId: number | null
  hideChatPreview: boolean
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): AppearanceState => ({
    theme: 'LIGHT',
    bgColor: '#f3efe0',
    darkBgColor: '#18181b',
    seasonalThemeId: null,
    hideChatPreview: false,
  }),

  getters: {
    isDark(): boolean {
      return this.theme === 'DARK'
    },
  },

  actions: {
    setTheme(theme: 'LIGHT' | 'DARK') {
      this.theme = theme
      this.applyTheme()
      this.persistToStorage()
    },

    setBgColor(color: string) {
      this.bgColor = color
      this.applyBgColor()
      this.persistToStorage()
    },

    setDarkBgColor(color: string) {
      this.darkBgColor = color
      this.applyBgColor()
      this.persistToStorage()
    },

    setHideChatPreview(hidden: boolean) {
      this.hideChatPreview = hidden
      this.persistToStorage()
    },

    applyTheme() {
      if (!import.meta.client) return
      const html = document.documentElement
      if (this.isDark) {
        html.classList.add('p-dark', 'dark')
      }
      else {
        html.classList.remove('p-dark', 'dark')
      }
      // テーマ切替時に背景色も再適用
      this.applyBgColor()
    },

    applyBgColor() {
      if (!import.meta.client) return
      // ダークモード時は darkBgColor を、ライトモード時は bgColor を適用する
      // （ダーク時に --bg-color を削除するとボディがクリーム色になるバグを根治）
      if (this.isDark) {
        document.documentElement.style.setProperty('--bg-color', this.darkBgColor)
      }
      else {
        document.documentElement.style.setProperty('--bg-color', this.bgColor)
      }
    },

    loadFromStorage() {
      if (!import.meta.client) return
      const saved = localStorage.getItem('appearance')
      if (saved) {
        try {
          const parsed = JSON.parse(saved)
          const themeVal = parsed.theme
          this.theme = (themeVal === 'LIGHT' || themeVal === 'DARK') ? themeVal : 'LIGHT'
          this.bgColor = parsed.bgColor ?? '#f3efe0'
          this.darkBgColor = parsed.darkBgColor ?? '#18181b'
          this.seasonalThemeId = parsed.seasonalThemeId ?? null
          this.hideChatPreview = parsed.hideChatPreview ?? false
        }
        catch {
          // ignore
        }
      }
      this.applyTheme()
      this.applyBgColor()
    },

    persistToStorage() {
      if (!import.meta.client) return
      const payload = JSON.stringify({
        theme: this.theme,
        bgColor: this.bgColor,
        darkBgColor: this.darkBgColor,
        seasonalThemeId: this.seasonalThemeId,
        hideChatPreview: this.hideChatPreview,
      })
      localStorage.setItem('appearance', payload)
      // SSR に初回テーマを伝えるために cookie にも鏡写し（HttpOnly 不可・クライアントで読む必要があるため）。
      // cookie は localStorage の「複製」として扱い、正本は localStorage。
      // max-age = 365 日（秒単位）
      const maxAge = 60 * 60 * 24 * 365
      document.cookie = `appearance=${encodeURIComponent(payload)}; path=/; max-age=${maxAge}; SameSite=Lax`
    },

    async syncWithServer() {
      try {
        const api = useApi()
        // 生成型 UpdateAppearanceRequest に合わせたボディ（darkBgColor は @NotNull 必須）
        const body: UpdateAppearanceRequest = {
          theme: this.theme,
          bgColor: this.bgColor,
          darkBgColor: this.darkBgColor,
          hideChatPreview: this.hideChatPreview,
          ...(this.seasonalThemeId !== null ? { seasonalThemeId: this.seasonalThemeId } : {}),
        }
        await api('/api/v1/settings/appearance', {
          method: 'PUT',
          body,
        })
      }
      catch {
        // silently fail - localStorage is primary
      }
    },

    async loadFromServer() {
      try {
        const api = useApi()
        // 生成型 AppearanceResponse を使用してサーバーレスポンスを型付け
        const response = await api<{ data: AppearanceResponse }>('/api/v1/settings/appearance')
        const t = response.data.theme
        this.theme = (t === 'LIGHT' || t === 'DARK') ? t : 'LIGHT'
        this.bgColor = response.data.bgColor ?? '#f3efe0'
        this.darkBgColor = response.data.darkBgColor ?? '#18181b'
        this.seasonalThemeId = response.data.seasonalThemeId ?? null
        this.hideChatPreview = response.data.hideChatPreview ?? false
        this.applyTheme()
        this.applyBgColor()
        this.persistToStorage()
      }
      catch {
        // fallback to localStorage
      }
    },
  },
})
