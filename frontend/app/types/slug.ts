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
