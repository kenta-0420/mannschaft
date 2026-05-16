/**
 * F18 Phase 2 — 組織（店主）スコープのポイントカード API 型定義。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / UC-9 / §6 / §12
 *
 * <p>店主ダッシュボード（3A）専用のリクエスト/レスポンス型をここに集約する。
 * ユーザー側ウォレットの型は {@code ~/types/pointCard.ts} を参照すること。
 *
 * <p>バックエンドの実体 DTO に厳密に整合させている:
 * <ul>
 *   <li>{@code PointCardProviderResponse}: id / code / displayName / category / type /
 *       organizationId / logoUrl / brandColor / defaultBarcodeFormat /
 *       cardNumberLengthHint / legalNotice / isActive
 *       — {@code createdAt} / {@code updatedAt} / {@code cardNumberRegex} は持たない</li>
 *   <li>{@code CustomerQrResponse}: providerId / displayName / deepLinkUrl / webUrl</li>
 *   <li>{@code StampEventResponse}: id / cardId / providerId / providerDisplayName /
 *       organizationId / delta / pressedByUserId / pressedByUserDisplayName /
 *       pressedAt / memo</li>
 * </ul>
 */
import type { BarcodeFormat, PointCardCategory } from './pointCard'

/**
 * 組織発行のポイントカードプロバイダー（自店マスタ）。
 *
 * <p>Phase 2 では {@code type} は常に {@code SELF_ISSUED_STAMP}（スタンプカード）。
 * {@code SELF_ISSUED_BALANCE}（残高型）は Phase 3 以降で追加予定。
 *
 * <p>バックエンド {@code PointCardProviderResponse} と完全に同形。
 */
export interface OrgPointCardProvider {
  /** UUIDv7 文字列 */
  id: string
  /** 内部識別子（{@code org_{orgId}_{rand8}} 形式でサーバー自動生成） */
  code: string
  displayName: string
  category: PointCardCategory
  /** Phase 2 では常に {@code SELF_ISSUED_STAMP} */
  type: 'SELF_ISSUED_STAMP' | 'EXTERNAL' | 'SELF_ISSUED_BALANCE'
  organizationId: number
  logoUrl: string | null
  /** HEX (#RRGGBB) */
  brandColor: string | null
  defaultBarcodeFormat: BarcodeFormat | null
  cardNumberLengthHint: string | null
  /** 「本機能は ○○ の公式アプリではありません」等の法的注意書き */
  legalNotice: string | null
  isActive: boolean
}

/**
 * プロバイダー新規発行リクエスト。
 *
 * <p>{@code type} は固定 {@code SELF_ISSUED_STAMP}（サーバーで自動設定、クライアントから送らない）。
 * {@code organizationId} はパスパラメータ {@code orgId} から自動充填されるため送らない。
 * {@code code} は {@code org_{orgId}_{rand8}} 形式でサーバー側自動生成。
 */
export interface CreateOrgProviderRequest {
  /** 表示名。必須・100 文字以内 */
  displayName: string
  /** ブランドカラー (#RRGGBB)。任意 */
  brandColor?: string
  /** ロゴ画像 URL。任意・500 文字以内 */
  logoUrl?: string
  /** カード番号バリデーション正規表現。任意・200 文字以内 */
  cardNumberRegex?: string
  /** カード番号桁数ヒント。任意・50 文字以内 */
  cardNumberLengthHint?: string
}

/**
 * プロバイダー編集（PATCH）リクエスト。全フィールド任意（null/undefined は変更なし）。
 *
 * <p>{@code type} / {@code organizationId} / {@code code} は不変のため含めない。
 */
export interface UpdateOrgProviderRequest {
  displayName?: string
  brandColor?: string
  logoUrl?: string
  cardNumberRegex?: string
  cardNumberLengthHint?: string
}

/**
 * 顧客追加用 QR レスポンス。
 *
 * <p>サーバーは URL のみ返却、QR 画像生成はフロントの qrcode ライブラリで行う。
 */
export interface CustomerQrResponse {
  providerId: string
  displayName: string
  /** モバイルアプリ用ディープリンク（{@code mannschaft://...}） */
  deepLinkUrl: string
  /** Web 用 URL（PWA・ブラウザフォールバック） */
  webUrl: string
}

/**
 * スタンプ押印リクエスト。
 *
 * <p>{@code delta} 通常 +1、誤押印取消で -1、特典付与で +2 など。0 は POINT_CARD_014 で拒否。
 * 範囲は -100〜100。
 */
export interface StampRequest {
  delta: number
  memo?: string
}

/**
 * スタンプ押印イベントレスポンス。
 *
 * <p>店主側 API のため、対象顧客カードの暗号化フィールド（displayName/nickname/barcodeValue/memo）は
 * 一切含めない。「カード ID + プロバイダー + 押印者 + delta + メモ」のみ。
 */
export interface StampEventResponse {
  id: string
  cardId: string
  providerId: string
  providerDisplayName: string | null
  organizationId: number
  delta: number
  pressedByUserId: number
  pressedByUserDisplayName: string | null
  pressedAt: string
  memo: string | null
}

/**
 * Spring Data {@code Page<T>} の最小サブセット（フロントで使うフィールドのみ）。
 *
 * <p>{@code OrgPointCardStampController#listOrgStamps} は ApiResponse でラップせず
 * 直接 Page を返すため、useApi のレスポンスは {@code PageResponse<StampEventResponse>} となる。
 */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
}
