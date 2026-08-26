/**
 * F21.1 GEO / セキュリティ — JSON-LD を <script type="application/ld+json"> の
 * 子要素として安全に埋め込むためのエスケープ関数。
 *
 * 【なぜ必要か】
 * JSON.stringify した文字列をそのまま script タグの children に流し込むと、
 * philosophy / name / city 等の自由入力に閉じスクリプトタグ文字列
 * （小なり記号 + slash + script + 大なり記号）が混入した場合に
 * スクリプトブレイクアウト（XSS）が発生する。公開ページは未認証アクセス可で
 * 影響が大きいため必ずエスケープする。unhead は小なり記号を自動エスケープしない。
 *
 * 【方針】
 * 小なり記号（U+003C `<`）のみを HTML 数値文字参照 `&lt;` 相当ではなく
 * Unicode エスケープ `<` に置換する。
 * - 小なり記号さえ無効化すれば HTML パーサが `</script>` 等の閉じタグを検出できなくなり
 *   ブレイクアウトを完全に防げる。
 * - `<` は JSON 文字列リテラルとして妥当なため、結果は引き続き JSON.parse 可能。
 *   （クローラ / 構造化データテストは JSON としてパースするため意味は変わらない）
 *
 * @param json JSON.stringify 済みの文字列
 * @returns 小なり記号を `<` へ置換した、HTML 埋め込みに安全な文字列
 */
export const escapeJsonLdForHtml = (json: string): string => {
  return json.replace(/</g, '\\u003C')
}
