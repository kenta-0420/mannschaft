import type { ProxyInputSource } from '~/types/proxy-input'

/**
 * 代理入力デスクの状態管理 composable。
 * useProxyDeskStore のラッパーとして、コンポーネントから使いやすい形で提供する。
 */
export function useProxyDesk() {
  const store = useProxyDeskStore()

  return {
    isPinned: computed(() => store.isPinned),
    pinnedSubjectUserId: computed(() => store.pinnedSubjectUserId),
    pinnedConsentId: computed(() => store.pinnedConsentId),
    inputSource: computed(() => store.inputSource),
    originalStorageLocation: computed(() => store.originalStorageLocation),

    /** 被代理ユーザーをデスクにピン留めする */
    pin(
      subjectUserId: number,
      consentId: number,
      inputSource: ProxyInputSource,
      originalStorageLocation = '',
    ) {
      store.pin(subjectUserId, consentId, inputSource, originalStorageLocation)
    },

    /** ピン留めを解除する */
    unpin() {
      store.unpin()
    },
  }
}
