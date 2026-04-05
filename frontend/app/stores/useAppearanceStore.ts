import { defineStore } from 'pinia'

type ThemeMode = 'LIGHT' | 'DARK' | 'SYSTEM'

interface AppearanceState {
  theme: ThemeMode
  bgColor: string
  seasonalThemeId: number | null
  hideChatPreview: boolean
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): AppearanceState => ({
    theme: 'SYSTEM',
    bgColor: '#ffffff',
    seasonalThemeId: null,
    hideChatPreview: false,
  }),

  getters: {
    isDark(): boolean {
      if (this.theme === 'DARK') return true
      if (this.theme === 'LIGHT') return false
      // SYSTEM: check OS preference
      if (import.meta.client) {
        return window.matchMedia('(prefers-color-scheme: dark)').matches
      }
      return false
    },
  },

  actions: {
    setTheme(theme: ThemeMode) {
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
        html.classList.add('p-dark')
      }
      else {
        html.classList.remove('p-dark')
      }
    },

    applyBgColor() {
      if (!import.meta.client) return
      document.documentElement.style.setProperty('--bg-color', this.bgColor)
    },

    loadFromStorage() {
      if (!import.meta.client) return
      const saved = localStorage.getItem('appearance')
      if (saved) {
        try {
          const parsed = JSON.parse(saved)
          this.theme = parsed.theme ?? 'SYSTEM'
          this.bgColor = parsed.bgColor ?? '#ffffff'
          this.seasonalThemeId = parsed.seasonalThemeId ?? null
          this.hideChatPreview = parsed.hideChatPreview ?? false
        }
        catch {
          // ignore
        }
      }
      this.applyTheme()
      this.applyBgColor()
      // Watch system theme changes
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
        if (this.theme === 'SYSTEM') this.applyTheme()
      })
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
        this.theme = response.data.theme
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
