// フレンド領域はBEの日本語messageより利用中の言語を優先する。
const FRIEND_I18N_CODES = new Set([
  'SOCIAL_101',
  'SOCIAL_102',
  'SOCIAL_103',
  'SOCIAL_104',
  'SOCIAL_105',
  'SOCIAL_106',
  'SOCIAL_107',
  'SOCIAL_108',
  'SOCIAL_109',
  'SOCIAL_110',
  'SOCIAL_111',
  'SOCIAL_112',
  'SOCIAL_113',
  'SOCIAL_120',
  'SOCIAL_121',
  'SOCIAL_122',
  'SOCIAL_123',
  'SOCIAL_124',
  'SOCIAL_125',
  'SOCIAL_130',
  'SOCIAL_131',
  'SOCIAL_132',
  'SOCIAL_133',
  'SOCIAL_134',
])

// サーバーのフィールドエラーメッセージ（英語）→ i18n バリデーションキーのマッピング
const FIELD_ERROR_PATTERNS: Array<{ pattern: RegExp; key: string }> = [
  { pattern: /required|must not be (blank|empty|null)/i, key: 'required' },
  { pattern: /email|must be a valid email/i, key: 'email' },
  { pattern: /too short|at least (\d+) char/i, key: 'min_length' },
  { pattern: /too long|at most (\d+) char|maximum (\d+)/i, key: 'max_length' },
  // AUTH_072: 郵便番号フォーマット不正（BE エラーメッセージに "postal" を含む場合に解決）
  { pattern: /postal.*format|format.*postal/i, key: 'postal_code_format' },
  // AUTH_071: 郵便番号必須（BE エラーメッセージに "postal" + "required" を含む場合）
  { pattern: /postal.*required|required.*postal/i, key: 'postal_code_required' },
  { pattern: /invalid (format|value)|not valid/i, key: 'invalid_format' },
  { pattern: /do not match|does not match/i, key: 'password_mismatch' },
]

export const useErrorHandler = () => {
  const notification = useNotification()
  const { t, te } = useI18n()
  const errorReport = useErrorReport()

  // #2426: BE（CommonErrorCode）が理由入りの具体的な message を返している場合、
  // 汎用 i18n キー（例: error.COMMON_001）で上書きして理由を握りつぶさない。
  // BE message を最優先し、無ければ従来どおり i18n キー→汎用文言にフォールバックする。
  const resolveMessage = (code: string, fallback?: string, context?: string): string => {
    const key = `error.${code}`
    if (code === 'COMMON_003' && context === 'team-loyalty') return t('loyalty.error.changed')
    if (code === 'MEMBERSHIP_003' && context === 'team-loyalty')
      return t('loyalty.memberQr.disabled')
    if (code.startsWith('LOYALTY_')) {
      const loyaltyKeys: Record<string, string> = {
        LOYALTY_001: 'loadErrorState.states.notFoundOrForbidden.message',
        LOYALTY_002: 'loadErrorState.states.forbidden.message',
        LOYALTY_003: 'loyalty.error.ineligible',
        LOYALTY_004: 'loyalty.program.disabled',
        LOYALTY_005: 'loyalty.error.alreadyAwarded',
        LOYALTY_006: 'loyalty.error.changed',
        LOYALTY_007: 'loyalty.error.changed',
        LOYALTY_008: 'loyalty.error.insufficient',
        LOYALTY_009: 'loyalty.error.changed',
        LOYALTY_010: 'loyalty.self.rescan',
        LOYALTY_011: 'loyalty.self.rescan',
        LOYALTY_012: 'error.server_retry',
        LOYALTY_013: 'loyalty.error.changed',
        LOYALTY_014: 'loyalty.error.changed',
        LOYALTY_015: 'loyalty.requests.selfApprovalForbidden',
      }
      const loyaltyKey = loyaltyKeys[code]
      if (loyaltyKey) return t(loyaltyKey)
    }
    // CMP-260920-1040 AC-36: 確認通知は errorCode ごとの6言語文言を表示する。
    // 確認通知とチームの固定不在/復元409だけ登録済みi18nを優先し、他codeの具体的理由は維持する。
    if (
      (FRIEND_I18N_CODES.has(code) ||
        code.startsWith('CONFIRMABLE_NOTIFICATION_') ||
        code === 'TEAM_001' ||
        code === 'TEAM_006') &&
      te(key)
    )
      return t(key)
    if (fallback) return fallback
    if (te(key)) return t(key)
    return t('error.unknown')
  }

  const handleApiError = (error: unknown, context?: string): void => {
    const apiError = error as {
      data?: { error?: { code?: string; message?: string } }
      statusCode?: number
    }

    // バックエンドへ静かに送信（4xx含む全エラーを記録）
    const reportedError = context === 'team-loyalty'
      ? (() => {
          // QR を含み得る FetchError の message/body/cause を報告基盤へ渡さない。
          const rawCode = apiError?.data?.error?.code
          const code = typeof rawCode === 'string'
            && /^(LOYALTY_00[1-9]|LOYALTY_01[0-5]|COMMON_003|MEMBERSHIP_003)$/.test(rawCode)
            ? rawCode : 'UNKNOWN'
          const rawStatus = apiError?.statusCode
          const status = typeof rawStatus === 'number' && Number.isInteger(rawStatus)
            && rawStatus >= 100 && rawStatus <= 599 ? rawStatus : 0
          return new Error(`Team loyalty API error (${code}; HTTP ${status})`)
        })()
      : error
    errorReport.captureQuiet(reportedError, { context })

    // F20.1: ENTITLEMENT_003（402）は useApi の共通ハンドラが既にグローバルペイウォール
    // モーダルを開いている（usePaywallStore）。ここで追加のトーストを出すと二重表示になるため
    // 明示的にスキップする（設計書 04 §2）。
    if (apiError?.data?.error?.code === 'ENTITLEMENT_003') {
      return
    }

    if (apiError?.data?.error?.code) {
      const message = resolveMessage(apiError.data.error.code, apiError.data.error.message, context)
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

  // エイリアス（handleError 形式でも使えるようにする）
  const handleError = handleApiError

  return {
    resolveMessage,
    handleApiError,
    handleError,
    getFieldErrors,
  }
}
