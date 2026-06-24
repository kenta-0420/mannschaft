import type { PostalCodePolicy } from '~/types/postal'

// モジュールスコープキャッシュ（アプリ全体で一度だけ取得する）
let _cached: PostalCodePolicy[] | null = null
let _loadingPromise: Promise<PostalCodePolicy[]> | null = null

/**
 * 国別郵便番号バリデーション composable。
 *
 * BE の GET /api/v1/postal-code/policies（permitAll）を単一真実源として使用する。
 * 早期フィードバック用クライアントサイド検証であり、BE の 400 検証はそのまま有効。
 */
export function usePostalCodeValidation() {
  const api = useApi()

  /**
   * ポリシー一覧をフェッチしてキャッシュに格納する。
   * 複数箇所から同時に呼ばれても Promise を共有して 1 回しかリクエストしない。
   */
  async function ensureLoaded(): Promise<PostalCodePolicy[]> {
    if (_cached !== null) return _cached

    if (_loadingPromise !== null) return _loadingPromise

    _loadingPromise = api<{ data: PostalCodePolicy[] }>('/api/v1/postal-code/policies', {
      // 未認証でも叩けるため credentials は不要だが、useApi のデフォルト（include）で問題なし
    })
      .then((res) => {
        _cached = res.data
        return _cached
      })
      .catch(() => {
        // 通信失敗時はキャッシュしない（次回再試行できるよう Promise をクリアする）
        _loadingPromise = null
        return [] as PostalCodePolicy[]
      })
      .finally(() => {
        _loadingPromise = null
      })

    return _loadingPromise
  }

  /**
   * 指定国コードに対応するポリシーを返す。
   * キャッシュ未ロードの場合は undefined を返す（同期アクセス用）。
   */
  function getPolicy(countryCode: string): PostalCodePolicy | undefined {
    return (_cached ?? []).find((p) => p.countryCode === countryCode.toUpperCase())
  }

  /**
   * 指定国コードの郵便番号バリデーションが対応しているかどうかを返す。
   */
  function isSupported(countryCode: string): boolean {
    return getPolicy(countryCode) !== undefined
  }

  /**
   * 生入力値をポリシーの pattern で検証する。
   * - 正規化は行わない（"111" は false）。
   * - 対応国コードが未登録の場合は false を返す。
   */
  function validateFormat(countryCode: string, raw: string): boolean {
    const policy = getPolicy(countryCode)
    if (!policy) return false
    return new RegExp(policy.pattern).test(raw)
  }

  return {
    ensureLoaded,
    getPolicy,
    isSupported,
    validateFormat,
  }
}
