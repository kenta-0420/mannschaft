const ERROR_MESSAGES: Record<string, string> = {
  // Common
  COMMON_001: '入力内容に不備があります',
  COMMON_002: 'この操作を行う権限がありません',
  COMMON_003: '他のユーザーによって更新されました。画面を更新してください',
  COMMON_999: 'システムエラーが発生しました',

  // Auth
  AUTH_001: 'メールアドレスまたはパスワードが正しくありません',
  AUTH_002: 'セッションの有効期限が切れました。再度ログインしてください',
  AUTH_003: 'メールアドレスは既に登録されています',
  AUTH_004: 'アカウントが無効化されています',
  AUTH_005: '確認コードが正しくありません',
}

export const useErrorHandler = () => {
  const notification = useNotification()

  const resolveMessage = (code: string, fallback?: string): string => {
    return ERROR_MESSAGES[code] ?? fallback ?? 'エラーが発生しました'
  }

  const handleApiError = (error: unknown): void => {
    // Check if it's an API error response with error code
    const apiError = error as { data?: { error?: { code?: string; message?: string } }; statusCode?: number }

    if (apiError?.data?.error?.code) {
      const message = resolveMessage(apiError.data.error.code, apiError.data.error.message)
      notification.error('エラー', message)
      return
    }

    // Fallback for network errors or unexpected errors
    if (apiError?.statusCode && apiError.statusCode >= 500) {
      notification.error('サーバーエラー', 'サーバーに接続できません。しばらくしてから再度お試しください')
      return
    }

    notification.error('エラー', 'エラーが発生しました')
  }

  const getFieldErrors = (error: unknown): Record<string, string> => {
    const apiError = error as { data?: { error?: { fieldErrors?: Array<{ field: string; message: string }> } } }
    const fieldErrors: Record<string, string> = {}
    if (apiError?.data?.error?.fieldErrors) {
      for (const fe of apiError.data.error.fieldErrors) {
        fieldErrors[fe.field] = fe.message
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
