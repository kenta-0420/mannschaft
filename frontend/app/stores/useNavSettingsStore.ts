import { defineStore } from 'pinia'
import type { NavFeatureItem } from '~/types/nav'

const STORAGE_KEY = 'nav-settings'

export const useNavSettingsStore = defineStore('navSettings', {
  state: () => ({
    // features は表示順（個人並び順 + マスタ補完）で BE がソート済みの配列。
    // ストアでは配列順をそのまま尊重し、再ソートしない。
    features: [] as NavFeatureItem[],
    loaded: false,
  }),

  getters: {
    // BE が返した表示順（features の配列順）を尊重し、visible のみで絞り込む。
    visibleFeatures: (state): NavFeatureItem[] =>
      state.features.filter(f => f.visible),
    visibleMobileFeatures: (state): NavFeatureItem[] =>
      state.features.filter(f => f.visible && f.mobileVisible),
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
        // BE は個人並び順 + マスタ補完でソート済み。配列順をそのまま採用する。
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
        // 表示順は維持したまま保存する（現在の features 配列順）。
        const order = this.features.map(f => f.key)
        const { updateNavSettings } = useNavSettingsApi()
        await updateNavSettings(hiddenKeys, order)
      } catch {
        // ロールバック
        this.features = prev
        this.persistToStorage()
        const { showError } = useNotification()
        // Pinia アクション（setup 外）では useI18n() が使えないため $i18n 経由で翻訳する。
        const t = (key: string) => useNuxtApp().$i18n.t(key)
        showError(t('settings.navigation.saveError'))
      }
    },

    /**
     * D&D 並び替え確定。newOrderKeys の順に features を並べ替え、楽観更新 → PUT。
     * 失敗時は元の順序にロールバックしトースト表示する。
     */
    async reorderNav(newOrderKeys: string[]) {
      const prev = this.features.map(f => ({ ...f }))

      // newOrderKeys の順で features を並べ替える（未知 key は無視、欠落 key は末尾維持）。
      const byKey = new Map(this.features.map(f => [f.key, f]))
      const reordered: NavFeatureItem[] = []
      const placed = new Set<string>()
      for (const key of newOrderKeys) {
        const f = byKey.get(key)
        if (f && !placed.has(key)) {
          reordered.push(f)
          placed.add(key)
        }
      }
      for (const f of this.features) {
        if (!placed.has(f.key)) {
          reordered.push(f)
          placed.add(f.key)
        }
      }
      this.features = reordered
      this.persistToStorage()

      try {
        const hiddenKeys = this.features
          .filter(f => !f.visible && !f.fixed)
          .map(f => f.key)
        const order = this.features.map(f => f.key)
        const { updateNavSettings } = useNavSettingsApi()
        await updateNavSettings(hiddenKeys, order)
      } catch {
        // ロールバック
        this.features = prev
        this.persistToStorage()
        const { showError } = useNotification()
        // Pinia アクション（setup 外）では useI18n() が使えないため $i18n 経由で翻訳する。
        const t = (key: string) => useNuxtApp().$i18n.t(key)
        showError(t('settings.navigation.saveError'))
      }
    },

    async resetToDefault() {
      const { updateNavSettings } = useNavSettingsApi()
      // navDisplayOrder=undefined を送るとマスタ順にリセットされる。
      await updateNavSettings([])
      await this.loadFromServer()
    },
  },
})
