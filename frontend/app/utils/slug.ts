/**
 * slug ユーティリティ
 *
 * チーム・組織の公開 URL スラッグ（英小文字・数字・ハイフン、3〜30 文字）に関する
 * 生成・バリデーション関数を提供する。
 */

/**
 * 任意の文字列からスラッグを自動生成する。
 *
 * - 小文字変換
 * - 英数字以外の連続 → ハイフン 1 個に変換
 * - 先頭・末尾のハイフン除去
 * - 30 文字に切り詰め
 * - 生成結果が 3 文字未満の場合は 'team' を返す
 *
 * @param name チーム名など任意の文字列
 * @returns 有効なスラッグ（最低 4 文字 'team' か 3〜30 文字の英数字・ハイフン）
 */
export function generateSlug(name: string): string {
  const result = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 30)
  return result.length >= 3 ? result : 'team'
}

/**
 * 文字列がスラッグとして有効かどうかを検証する。
 *
 * 有効な条件:
 * - 3〜30 文字
 * - 英小文字・数字・ハイフンのみ
 * - 先頭・末尾がハイフン以外
 *
 * @param slug 検証するスラッグ
 * @returns 有効なら true
 */
export function isValidSlug(slug: string): boolean {
  return /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/.test(slug) || /^[a-z0-9]{3}$/.test(slug)
}

/**
 * BE #1538 の slug 制約に厳密準拠した形式チェック。
 *
 * 制約（BE `^[a-z0-9-]{3,30}$` + 先頭/末尾ハイフン不可 + 連続ハイフン不可）:
 * - 3〜30 文字
 * - 英小文字・数字・ハイフンのみ
 * - 先頭・末尾がハイフンでない
 * - ハイフンが 2 つ以上連続しない
 *
 * 送信前のクライアント側ガード兼、可用性チェック呼び出し前の事前判定に使う。
 *
 * @param slug 検証するスラッグ
 * @returns 形式が有効なら true
 */
export function isSlugFormatValid(slug: string): boolean {
  if (slug.length < 3 || slug.length > 30) return false
  if (!/^[a-z0-9-]+$/.test(slug)) return false
  if (slug.startsWith('-') || slug.endsWith('-')) return false
  if (slug.includes('--')) return false
  return true
}
