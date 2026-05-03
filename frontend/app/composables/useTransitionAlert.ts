import { ref, type Ref } from 'vue'
import type { TransitionAlertResponse } from '~/types/school'

export function useTransitionAlert(teamId: Ref<number>) {
  const api = useTransitionAlertApi()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const { t } = useI18n()

  const alerts = ref<TransitionAlertResponse[]>([])
  const loading = ref(false)
  const resolving = ref(false)
  const unresolvedCount = ref(0)
  const totalCount = ref(0)

  /**
   * アラート一覧を読み込む。
   *
   * @param date           対象日（YYYY-MM-DD 形式）
   * @param unresolvedOnly true の場合は未解決のみ取得
   */
  async function loadAlerts(date: string, unresolvedOnly: boolean): Promise<void> {
    loading.value = true
    try {
      const res = await api.getAlerts(teamId.value, date, unresolvedOnly)
      alerts.value = res.alerts
      unresolvedCount.value = res.unresolvedCount
      totalCount.value = res.totalCount
    } catch {
      notifyError(t('school.transitionAlert.title'))
    } finally {
      loading.value = false
    }
  }

  /**
   * アラートを解決済みにする。
   *
   * @param alertId アラートID
   * @param note    解決理由
   * @returns 解決後のアラート（失敗時は null）
   */
  async function resolveAlert(alertId: number, note: string): Promise<TransitionAlertResponse | null> {
    resolving.value = true
    try {
      const updated = await api.resolveAlert(teamId.value, alertId, note)
      // ローカル状態を即時更新
      const index = alerts.value.findIndex((a) => a.id === alertId)
      if (index !== -1) {
        alerts.value[index] = updated
        unresolvedCount.value = alerts.value.filter((a) => !a.resolved).length
      }
      notifySuccess(t('school.transitionAlert.resolveSuccess'))
      return updated
    } catch {
      notifyError(t('school.transitionAlert.title'))
      return null
    } finally {
      resolving.value = false
    }
  }

  return {
    alerts,
    loading,
    resolving,
    unresolvedCount,
    totalCount,
    loadAlerts,
    resolveAlert,
  }
}
