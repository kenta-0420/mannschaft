import { defineStore } from 'pinia'

/**
 * F08.9 P3c 後見切替ストア。
 *
 * 保護者が「子として操作」しているときの状態を保持する。
 * useApi() の onRequest フックでこのストアを参照し、
 * X-Proxy-For-User-Id ヘッダを自動付与する。
 *
 * proxyDeskStore.isPinned（代理入力モード）とは排他。
 * 両方を同時に active にしない制約はページ側で担保する。
 */
export const useGuardianshipSwitchStore = defineStore('guardianshipSwitch', () => {
  /** 現在操作中の子。null なら通常モード。 */
  const activeChild = ref<{ childUserId: number; displayName: string | null } | null>(null)

  /** 後見切替として子の操作中かどうか。 */
  const isActingAs = computed(() => activeChild.value !== null)

  /**
   * 後見切替を開始する。
   * @param child 切替対象の子（childUserId と displayName）
   */
  function startSwitch(child: { childUserId: number; displayName: string | null }) {
    activeChild.value = child
  }

  /**
   * 後見切替を終了する。
   */
  function endSwitch() {
    activeChild.value = null
  }

  return { activeChild, isActingAs, startSwitch, endSwitch }
})
