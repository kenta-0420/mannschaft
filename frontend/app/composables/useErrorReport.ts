interface ErrorReportState {
  visible: boolean
  submitting: boolean
  submitted: boolean
  commentSent: boolean
  errorMessage: string
  stackTrace: string
  pageUrl: string
  userAgent: string
  requestId: string
  context: string
}

// ダイアログを一定時間内に何度も開かないためのクールダウン (ms)
const ERROR_REPORT_COOLDOWN_MS = 60_000
let _lastReportShownAt = 0

export const useErrorReport = () => {
  const state = useState<ErrorReportState>('errorReport', () => ({
    visible: false,
    submitting: false,
    submitted: false,
    commentSent: false,
    errorMessage: '',
    stackTrace: '',
    pageUrl: '',
    userAgent: '',
    requestId: '',
    context: '',
  }))

  const config = useRuntimeConfig()

  const capture = (
    error: unknown,
    meta?: { context?: string; apiUrl?: string; statusCode?: number; requestId?: string },
  ): void => {
    // 既に表示中、またはクールダウン中は無視
    if (state.value.visible) return
    if (Date.now() - _lastReportShownAt < ERROR_REPORT_COOLDOWN_MS) {
      console.error('[ErrorReport] (suppressed by cooldown)', error, meta)
      return
    }

    const err = error instanceof Error ? error : new Error(String(error))

    let errorMessage = err.message.slice(0, 1000)
    if (meta?.statusCode && meta?.apiUrl) {
      errorMessage = `[${meta.statusCode}] ${meta.apiUrl}: ${errorMessage}`.slice(0, 1000)
    }

    _lastReportShownAt = Date.now()
    state.value = {
      visible: true,
      submitting: false,
      submitted: false,
      commentSent: false,
      errorMessage,
      stackTrace: (err.stack ?? '').slice(0, 2000),
      pageUrl: typeof window !== 'undefined' ? window.location.href : '',
      userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
      requestId: meta?.requestId ?? '',
      context: meta?.context ?? '',
    }

    console.error('[ErrorReport]', err, meta)

    // Auto-submit immediately (fire & forget)
    _sendReport()
  }

  const _sendReport = async (userComment?: string): Promise<void> => {
    if (state.value.submitting) return

    state.value.submitting = true

    try {
      const authStore = useAuthStore()

      await $fetch(`${config.public.apiBase}/api/v1/error-reports`, {
        method: 'POST',
        headers: authStore.accessToken ? { Authorization: `Bearer ${authStore.accessToken}` } : {},
        body: {
          errorMessage: state.value.errorMessage,
          stackTrace: state.value.stackTrace || undefined,
          pageUrl: state.value.pageUrl,
          userAgent: state.value.userAgent || undefined,
          userComment: userComment || undefined,
          userId: authStore.currentUser?.id ?? undefined,
          occurredAt: new Date().toISOString(),
          requestId: state.value.requestId || undefined,
          context: state.value.context || undefined,
        },
      })

      if (userComment) {
        state.value.commentSent = true
      } else {
        state.value.submitted = true
      }
    } catch {
      // Don't let error reporting cause more errors
      console.error('[ErrorReport] Failed to submit error report')
      if (!userComment) {
        state.value.submitted = true // hide submitting state even on failure
      }
    } finally {
      state.value.submitting = false
    }
  }

  /**
   * ダイアログを開かずにエラーをバックエンドへ送信する（ウィジェット用）
   * ネットワーク障害（サーバー停止・接続拒否）の場合は送信しない
   */
  const captureQuiet = (error: unknown, meta?: { context?: string; requestId?: string }): void => {
    if (import.meta.server) return
    const err = error instanceof Error ? error : new Error(String(error))

    // ネットワーク障害はサーバーも落ちているため送信不要
    const msg = err.message.toLowerCase()
    if (
      msg.includes('failed to fetch') ||
      msg.includes('network error') ||
      msg.includes('err_connection_refused') ||
      msg.includes('<no response>')
    ) {
      console.warn('[ErrorReport:quiet] network error (skipped)', meta?.context)
      return
    }

    const authStore = useAuthStore()
    console.error('[ErrorReport:quiet]', { context: meta?.context }, err)
    $fetch(`${config.public.apiBase}/api/v1/error-reports`, {
      method: 'POST',
      headers: authStore.accessToken ? { Authorization: `Bearer ${authStore.accessToken}` } : {},
      body: {
        errorMessage: err.message.slice(0, 1000),
        stackTrace: err.stack?.slice(0, 2000) || undefined,
        pageUrl: typeof window !== 'undefined' ? window.location.href : '',
        userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
        userId: authStore.currentUser?.id ?? undefined,
        occurredAt: new Date().toISOString(),
        context: meta?.context || undefined,
        requestId: meta?.requestId || undefined,
      },
    }).catch(() => {
      /* ログ送信失敗は無視 */
    })
  }

  const submitComment = (userComment: string): Promise<void> => {
    return _sendReport(userComment)
  }

  const close = (): void => {
    state.value.visible = false
    state.value.submitting = false
    state.value.submitted = false
    state.value.commentSent = false
  }

  return {
    state: readonly(state),
    capture,
    captureQuiet,
    submitComment,
    close,
  }
}
