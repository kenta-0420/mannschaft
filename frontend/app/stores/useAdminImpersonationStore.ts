import { defineStore } from 'pinia'

/**
 * 管理者変身ストア（F10.1）。
 * システム管理者が特定ユーザーの視点でアプリを操作するための状態管理。
 * isImpersonating が true の間、useApi の onRequest が
 * X-Admin-Impersonate-User-Id ヘッダーを自動付与する。
 */
export const useAdminImpersonationStore = defineStore('adminImpersonation', () => {
  const targetUserId = ref<number | null>(null)
  const targetUserLabel = ref<string>('')

  const isImpersonating = computed(() => targetUserId.value !== null)

  /**
   * 変身を開始する。
   * @param userId 変身先ユーザー ID
   * @param label 変身中バナーに表示するラベル
   */
  function startImpersonation(userId: number, label: string) {
    targetUserId.value = userId
    targetUserLabel.value = label
  }

  /**
   * 変身を終了してデフォルト状態に戻す。
   */
  function stopImpersonation() {
    targetUserId.value = null
    targetUserLabel.value = ''
  }

  return {
    targetUserId,
    targetUserLabel,
    isImpersonating,
    startImpersonation,
    stopImpersonation,
  }
})
