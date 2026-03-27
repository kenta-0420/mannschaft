interface ErrorReportState {
  visible: boolean
  submitting: boolean
  submitted: boolean
  errorMessage: string
  stackTrace: string
  pageUrl: string
  userAgent: string
}

export const useErrorReport = () => {
  const state = useState<ErrorReportState>('errorReport', () => ({
    visible: false,
    submitting: false,
    submitted: false,
    errorMessage: '',
    stackTrace: '',
    pageUrl: '',
    userAgent: '',
  }))

  const config = useRuntimeConfig()

  const capture = (error: unknown, meta?: { context?: string }): void => {
    // Prevent recursive error reporting
    if (state.value.visible) return

    const err = error instanceof Error ? error : new Error(String(error))

    state.value = {
      visible: true,
      submitting: false,
      submitted: false,
      errorMessage: err.message,
      stackTrace: (err.stack ?? '').slice(0, 2000),
      pageUrl: typeof window !== 'undefined' ? window.location.href : '',
      userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
    }

    // Always log to console
    console.error('[ErrorReport]', err, meta)
  }

  const submit = async (userComment?: string): Promise<void> => {
    if (state.value.submitting) return

    state.value.submitting = true

    try {
      const authStore = useAuthStore()

      await $fetch(`${config.public.apiBase}/api/v1/error-reports`, {
        method: 'POST',
        body: {
          error_message: state.value.errorMessage,
          stack_trace: state.value.stackTrace,
          page_url: state.value.pageUrl,
          user_agent: state.value.userAgent,
          user_comment: userComment ?? null,
          user_id: authStore.currentUser?.id ?? null,
          occurred_at: new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Tokyo' }).replace(' ', 'T'),
        },
      })

      state.value.submitted = true

      // Auto-close after 3 seconds
      setTimeout(() => {
        close()
      }, 3000)
    } catch {
      // Don't let error reporting cause more errors
      console.error('[ErrorReport] Failed to submit error report')
      close()
    }
  }

  const close = (): void => {
    state.value.visible = false
    state.value.submitting = false
    state.value.submitted = false
  }

  return {
    state: readonly(state),
    capture,
    submit,
    close,
  }
}
