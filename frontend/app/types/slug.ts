/**
 * slug 可用性チェック関連の型。
 *
 * チーム/組織作成フォームのリアルタイム可用性チェック
 * （`GET /api/v1/teams/slug-available` / `GET /api/v1/organizations/slug-available`）の
 * レスポンス DTO を表す。BE #1538 と 1:1。
 *
 * ※ openapi.json / `types/generated` の再同期は別途行うため、ここでは手動型として定義する。
 */

/**
 * 可用性チェックの不可理由コード。
 *
 * BE は形式不正・予約語・重複・未指定のいずれでも常に 200 を返し、
 * `available=false` のとき `reason` に該当コードを格納する。
 */
export type SlugUnavailableReason =
  | 'SLUG_REQUIRED'
  | 'SLUG_INVALID_FORMAT'
  | 'SLUG_RESERVED'
  | 'SLUG_ALREADY_TAKEN'
  // BE #1542: 過去に別のチーム/組織が使っていた slug は履歴予約され、
  // 301 解決のために再利用できない（`*_063` SLUG_RETIRED）。
  | 'SLUG_RETIRED'

/**
 * slug 可用性チェックのレスポンス。
 *
 * - `available=true`: 使用可能（`reason` は無し）
 * - `available=false`: 使用不可。`reason` に理由コードが入る
 */
export interface SlugAvailabilityResponse {
  available: boolean
  reason?: SlugUnavailableReason
}

/**
 * slug 解決（旧 slug → 新 slug の 301 リダイレクト判定）レスポンス。
 *
 * 公開 EP `GET /api/v1/public/teams/slug-resolve?slug=x` /
 * `GET /api/v1/public/organizations/slug-resolve?slug=x`（permitAll・レート制限）の
 * レスポンス DTO。BE `SlugResolveResponse`（F01.2 §5.9.5）と 1:1。
 *
 * スコープ漏洩防止のため名前など実データは返さず `canonicalSlug` のみを返す。
 *
 * - `CURRENT`: 指定 slug がそのまま現行で有効（リダイレクト不要）
 * - `MOVED`: 旧 slug で、`canonicalSlug` の現行 slug へ 301 すべき
 * - `NOT_FOUND`: 現行にも履歴にも該当なし（404 相当）
 */
export interface SlugResolveResponse {
  status: 'CURRENT' | 'MOVED' | 'NOT_FOUND'
  /** `status === 'MOVED'` のときの現行 slug。それ以外は無し。 */
  canonicalSlug?: string
}
