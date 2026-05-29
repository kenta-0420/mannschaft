import { defineStore } from 'pinia'
import type { NavFeatureItem } from '~/types/nav'

const STORAGE_KEY = 'nav-settings'

export const useNavSettingsStore = defineStore('navSettings', {
  state: () => ({
    features: [] as NavFeatureItem[],
    loaded: false,
  }),

  getters: {
    visibleFeatures: (state): NavFeatureItem[] =>
      state.features
        .filter(f => f.visible)
        .sort((a, b) => a.sortOrder - b.sortOrder),
    visibleMobileFeatures: (state): NavFeatureItem[] =>
      state.features
        .filter(f => f.visible && f.mobileVisible)
        .sort((a, b) => a.sortOrder - b.sortOrder),
  },

  actions: {
    loadFromStorage() {
      if (!import.meta.client) return
      try {
        const saved = localStorage.getItem(STORAGE_KEY)
        if (saved) {
          this.features = JSON.parse(saved)
        }
      } catch {
        // ignore
      }
    },

    persistToStorage() {
      if (!import.meta.client) return
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.features))
    },

    async loadFromServer() {
      try {
        const { getNavSettings } = useNavSettingsApi()
        const res = await getNavSettings()
        this.features = res.features
        this.loaded = true
        this.persistToStorage()
      } catch {
        // fallback to localStorage
        this.loaded = true
      }
    },

    async setVisibility(key: string, visible: boolean) {
      // 楽観的更新
      const prev = this.features.map(f => ({ ...f }))
      this.features = this.features.map(f =>
        f.key === key ? { ...f, visible } : f
      )
      this.persistToStorage()

      try {
        const hiddenKeys = this.features
          .filter(f => !f.visible && !f.fixed)
          .map(f => f.key)
        const { updateNavSettings } = useNavSettingsApi()
        await updateNavSettings(hiddenKeys)
      } catch {
        // ロールバック
        this.features = prev
        this.persistToStorage()
        const { showError } = useNotification()
        showError('設定の保存に失敗しました')
      }
    },

    async resetToDefault() {
      const { updateNavSettings } = useNavSettingsApi()
      await updateNavSettings([])
      await this.loadFromServer()
    },
  },
})
