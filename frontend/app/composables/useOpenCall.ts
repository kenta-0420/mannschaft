/**
 * オープンコール（先着手挙げ型シフト交代）を管理する composable。
 * F03.5 §A-3 オープンコール に対応。
 */
export function useOpenCall() {
  const shiftApi = useShiftApi()
  const { showError, showSuccess } = useNotification()
  const { t } = useI18n()

  /** オープンコールに手を挙げる（先着） */
  async function claim(swapRequestId: number): Promise<void> {
    try {
      await shiftApi.claimOpenCall(swapRequestId)
      showSuccess(t('shift.openCall.claim'))
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('claim failed')
    }
  }

  /** オープンコールの候補者を選定する（申請者のみ） */
  async function selectClaimer(swapRequestId: number, claimedBy: number): Promise<void> {
    try {
      await shiftApi.selectClaimer(swapRequestId, claimedBy)
      showSuccess(t('shift.openCall.claim'))
    } catch {
      showError(t('shift.changeRequest.fetchError'))
      throw new Error('selectClaimer failed')
    }
  }

  return { claim, selectClaimer }
}
