// サーバーのフィールドエラーメッセージ（英語）→ i18n バリデーションキーのマッピング
const FIELD_ERROR_PATTERNS: Array<{ pattern: RegExp; key: string }> = [
  { pattern: /required|must not be (blank|empty|null)/i, key: 'required' },
  { pattern: /email|must be a valid email/i, key: 'email' },
  { pattern: /too short|at least (\d+) char/i, key: 'min_length' },
  { pattern: /too long|at most (\d+) char|maximum (\d+)/i, key: 'max_length' },
  { pattern: /invalid (format|value)|not valid/i, key: 'invalid_format' },
  { pattern: /do not match|does not match/i, key: 'password_mismatch' },
]

export const useErrorHandler = () => {
  const notification = useNotification()
  const { t, te } = useI18n()
  const errorReport = useErrorReport()

  const resolveMessage = (code: string, fallback?: string): string => {
    const key = `error.${code}`
    if (te(key)) return t(key)
    return fallback ?? t('error.unknown')
  }

  const handleApiError = (error: unknown, context?: string): void => {
    const apiError = error as {
      data?: { error?: { code?: string; message?: string } }
      statusCode?: number
    }

    // バックエンドへ静かに送信（4xx含む全エラーを記録）
    errorReport.captureQuiet(error, { context })

    if (apiError?.data?.error?.code) {
      const message = resolveMessage(apiError.data.error.code, apiError.data.error.message)
      notification.error(t('dialog.error'), message)
      return
    }

    if (apiError?.statusCode && apiError.statusCode >= 500) {
      notification.error(t('error.server'), t('error.server_retry'))
      return
    }

    notification.error(t('dialog.error'), t('error.unknown'))
  }

  const getFieldErrors = (error: unknown): Record<string, string> => {
    const apiError = error as {
      data?: { error?: { fieldErrors?: Array<{ field: string; message: string }> } }
    }
    const fieldErrors: Record<string, string> = {}
    if (apiError?.data?.error?.fieldErrors) {
      for (const fe of apiError.data.error.fieldErrors) {
        const matched = FIELD_ERROR_PATTERNS.find((p) => p.pattern.test(fe.message))
        fieldErrors[fe.field] = matched ? t(matched.key) : t('invalid_format')
      }
    }
    return fieldErrors
  }

  return {
    resolveMessage,
    handleApiError,
    getFieldErrors,
  }
}
