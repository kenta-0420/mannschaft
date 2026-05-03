import { defineStore } from 'pinia'
import type { ProxyInputSource } from '~/types/proxy-input'

/** localStorage 永続化データの構造 */
interface ProxyDeskStorage {
  pinnedSubjectUserId: number
  pinnedConsentId: number
  inputSource: ProxyInputSource
  originalStorageLocation: string
}

export const useProxyDeskStore = defineStore('proxyDesk', {
  state: () => ({
    pinnedSubjectUserId: null as number | null,
    pinnedConsentId: null as number | null,
    inputSource: 'PAPER_FORM' as ProxyInputSource,
    originalStorageLocation: '',
  }),

  getters: {
    isPinned: (state) => state.pinnedSubjectUserId !== null && state.pinnedConsentId !== null,
  },

  actions: {
    /** 代理入力デスクに被代理ユーザーをピン留めする */
    pin(
      subjectUserId: number,
      consentId: number,
      inputSource: ProxyInputSource,
      originalStorageLocation = '',
    ) {
      this.pinnedSubjectUserId = subjectUserId
      this.pinnedConsentId = consentId
      this.inputSource = inputSource
      this.originalStorageLocation = originalStorageLocation
      if (import.meta.client) {
        const data: ProxyDeskStorage = {
          pinnedSubjectUserId: this.pinnedSubjectUserId,
          pinnedConsentId: this.pinnedConsentId,
          inputSource: this.inputSource,
          originalStorageLocation: this.originalStorageLocation,
        }
        localStorage.setItem('proxyDesk', JSON.stringify(data))
      }
    },

    /** ピン留めを解除する */
    unpin() {
      this.pinnedSubjectUserId = null
      this.pinnedConsentId = null
      this.inputSource = 'PAPER_FORM'
      this.originalStorageLocation = ''
      if (import.meta.client) {
        localStorage.removeItem('proxyDesk')
      }
    },

    /** ページリロード後に localStorage から状態を復元する */
    restoreFromStorage() {
      if (!import.meta.client) return
      const raw = localStorage.getItem('proxyDesk')
      if (!raw) return
      try {
        const parsed = JSON.parse(raw) as Partial<ProxyDeskStorage>
        if (parsed.pinnedSubjectUserId && parsed.pinnedConsentId) {
          this.pinnedSubjectUserId = parsed.pinnedSubjectUserId
          this.pinnedConsentId = parsed.pinnedConsentId
          this.inputSource = parsed.inputSource ?? 'PAPER_FORM'
          this.originalStorageLocation = parsed.originalStorageLocation ?? ''
        }
      } catch {
        localStorage.removeItem('proxyDesk')
      }
    },
  },
})
