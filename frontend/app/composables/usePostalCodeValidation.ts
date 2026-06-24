import type { PostalCodePolicy } from '~/types/postal'

// モジュールスコープのリアクティブキャッシュ（アプリ全体で共有・リアクティブ）。
// ref をモジュールスコープに置くことで、_policies.value を読む computed が
// 依存追跡され、ensureLoaded() のロード完了時に自動再評価される。
const _policies = ref<PostalCodePolicy[] | null>(null)
// フェッチ中フラグ（二重リクエスト防止）。リアクティブ不要なのでプレーン変数。
let _fetchingPromise: Promise<void> | null = null

/**
 * 国別郵便番号バリデーション composable。
 *
 * BE の GET /api/v1/postal-code/policies（permitAll）を単一真実源として使用する。
 * 早期フィードバック用クライアントサイド検証であり、BE の 400 検証はそのまま有効。
 */
export function usePostalCodeValidation() {
  const api = useApi()

  /**
   * ポリシー一覧をフェッチしてリアクティブキャッシュに格納する。
   * 複数箇所から同時に呼ばれても Promise を共有して 1 回しかリクエストしない。
   */
  async function ensureLoaded(): Promise<PostalCodePolicy[]> {
    if (_policies.value !== null) return _policies.value

    // 既にフェッチ中なら待機する（二重リクエスト防止）
    if (_fetchingPromise !== null) {
      await _fetchingPromise
      return _policies.value ?? []
    }

    _fetchingPromise = api<{ data: PostalCodePolicy[] }>('/api/v1/postal-code/policies')
      .then((res) => {
        _policies.value = res.data
      })
      .catch(() => {
        // 通信失敗時はキャッシュしない（次回再試行できるよう null のまま）
        _policies.value = null
      })
      .finally(() => {
        _fetchingPromise = null
      })

    await _fetchingPromise
    return _policies.value ?? []
  }

  /**
   * ポリシーがロード済みかどうかを表すリアクティブな指標。
   * - false: 未ロード（_policies.value === null）。「未対応」注記・検証エラーを出してはならない
   * - true:  ロード済み（フェッチ成功）。isSupported / validateFormat の結果が確定的
   */
  const isLoaded = computed<boolean>(() => _policies.value !== null)

  /**
   * 指定国コードに対応するポリシーを返す。
   * キャッシュ未ロードの場合は undefined を返す（同期アクセス用）。
   */
  function getPolicy(countryCode: string): PostalCodePolicy | undefined {
    return (_policies.value ?? []).find((p) => p.countryCode === countryCode.toUpperCase())
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
    isLoaded,
    getPolicy,
    isSupported,
    validateFormat,
  }
}
