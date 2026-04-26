import { defineStore } from 'pinia'

type ThemeMode = 'LIGHT' | 'DARK'

interface AppearanceState {
  theme: ThemeMode
  bgColor: string
  seasonalThemeId: number | null
  hideChatPreview: boolean
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): AppearanceState => ({
    theme: 'LIGHT',
    bgColor: '#f3efe0',
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
      // テーマ切替時に背景色も再適用（ライトモード制御）
      this.applyBgColor()
    },

    applyBgColor() {
      if (!import.meta.client) return
      if (this.isDark) {
        // ダークモード時は背景色選択を無効化し、bg-surface-ground に戻す
        document.documentElement.style.removeProperty('--bg-color')
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
          const saved = parsed.theme
          this.theme = (saved === 'LIGHT' || saved === 'DARK') ? saved : 'LIGHT'
          this.bgColor = parsed.bgColor ?? '#f3efe0'
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
      localStorage.setItem('appearance', JSON.stringify({
        theme: this.theme,
        bgColor: this.bgColor,
        seasonalThemeId: this.seasonalThemeId,
        hideChatPreview: this.hideChatPreview,
      }))
    },

    async syncWithServer() {
      try {
        const api = useApi()
        await api('/api/v1/settings/appearance', {
          method: 'PUT',
          body: {
            theme: this.theme,
            bgColor: this.bgColor,
            seasonalThemeId: this.seasonalThemeId,
            hideChatPreview: this.hideChatPreview,
          },
        })
      }
      catch {
        // silently fail - localStorage is primary
      }
    },

    async loadFromServer() {
      try {
        const api = useApi()
        const response = await api<{ data: AppearanceState }>('/api/v1/settings/appearance')
        const t = response.data.theme as string
        this.theme = (t === 'LIGHT' || t === 'DARK') ? t : 'LIGHT'
        this.bgColor = response.data.bgColor
        this.seasonalThemeId = response.data.seasonalThemeId
        this.hideChatPreview = response.data.hideChatPreview
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
