/**
 * F10.6 Phase 10-δ — SLA 残り時間の計算・表示ユーティリティ。
 * Date.now() を使うため SSR では ClientOnly 内で使用すること。
 */
export function useSlaDueAt() {
  const { t } = useI18n()

  function slaStatus(slaDueAt: string | null | undefined): 'overdue' | 'warning' | 'ok' | 'none' {
    if (!slaDueAt) return 'none'
    const dueMs = new Date(slaDueAt).getTime() - Date.now()
    if (dueMs < 0) return 'overdue'
    if (dueMs < 60 * 60 * 1000) return 'warning'
    return 'ok'
  }

  function slaLabel(slaDueAt: string | null | undefined): string {
    if (!slaDueAt) return '-'
    const dueMs = new Date(slaDueAt).getTime() - Date.now()
    if (dueMs < 0) {
      const overHours = Math.floor(-dueMs / (60 * 60 * 1000))
      return t('error_report.sla.overdue_by', { hours: overHours })
    }
    const remHours = Math.floor(dueMs / (60 * 60 * 1000))
    if (remHours < 1) {
      const remMins = Math.max(1, Math.floor(dueMs / (60 * 1000)))
      return t('error_report.sla.remaining_minutes', { minutes: remMins })
    }
    if (remHours < 24) {
      return t('error_report.sla.remaining_hours', { hours: remHours })
    }
    const remDays = Math.floor(remHours / 24)
    return t('error_report.sla.remaining_days', { days: remDays })
  }

  return { slaStatus, slaLabel }
}
