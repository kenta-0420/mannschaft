/**
 * シフト PDF ダウンロードを管理する composable。
 * F03.5 §PDF出力 に対応。
 */
export function useShiftPdf() {
  const shiftApi = useShiftApi()
  const { showError } = useNotification()
  const { t } = useI18n()

  const isDownloading = ref(false)
  const error = ref<string | null>(null)

  /**
   * シフト PDF をダウンロードする。
   * Blob を取得し、URL.createObjectURL → a タグクリック → revokeObjectURL の順で処理する。
   */
  async function download(
    scheduleId: number,
    layout: 'team' | 'personal',
    filename?: string,
  ): Promise<void> {
    isDownloading.value = true
    error.value = null

    try {
      const blob = await shiftApi.downloadShiftPdf(scheduleId, layout)
      const url = URL.createObjectURL(blob)

      const defaultFilename =
        layout === 'team'
          ? `shift-team-${scheduleId}.pdf`
          : `shift-personal-${scheduleId}.pdf`

      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filename ?? defaultFilename
      document.body.appendChild(anchor)
      anchor.click()
      document.body.removeChild(anchor)

      URL.revokeObjectURL(url)
    } catch {
      error.value = t('shift.pdf.error')
      showError(t('shift.pdf.error'))
    } finally {
      isDownloading.value = false
    }
  }

  return { isDownloading, error, download }
}
