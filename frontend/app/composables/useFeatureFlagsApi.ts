export interface PublicFeatureFlag {
  flagKey: string
  enabled: boolean
}

/**
 * 一般ユーザー向け公開フィーチャーフラグ読取API（Gate基盤工事①）。
 * 管理者専用の {@link useSystemAdminApi} とは別系統。認証済みユーザーなら誰でも呼べる。
 */
export function useFeatureFlagsApi() {
  const api = useApi()

  async function getPublicFlags(): Promise<PublicFeatureFlag[]> {
    const res = await api<{ data: PublicFeatureFlag[] }>('/api/v1/feature-flags')
    return res.data
  }

  return { getPublicFlags }
}
