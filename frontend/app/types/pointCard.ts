/**
 * F18 個人ポイントカードウォレットの型定義。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §6 (API)
 *
 * バックエンド (Spring Boot + Jackson) はデフォルトで camelCase JSON を返す。
 * ApiResponse.of() でラップされるため、レスポンスは常に { data: T } 形式。
 *
 * 暗号化フィールドは API 詳細経路でのみ復号値を含む（一覧では返さない）。
 * 詳細ガード:
 *   - 一覧 (UserPointCardListItem): barcodeValue / nickname / memo を返さない
 *   - 詳細 (UserPointCardDetail): barcodeValue / nickname / memo を含む
 *   - グループ提示 (PointCardGroupItem): バックエンドから 1 SQL で復号値を含めて返す
 */

// =====================================================================
// Enum 型
// =====================================================================

/** バーコード形式。Phase 1 でサポートする形式と「NONE」（バーコード持たないカード）。 */
export type BarcodeFormat =
  | 'CODE128'
  | 'CODE39'
  | 'EAN13'
  | 'EAN8'
  | 'JAN13'
  | 'QR'
  | 'PDF417'
  | 'ITF'
  | 'NONE'

/** プロバイダー種別。Phase 1 は EXTERNAL のみ実利用、Phase 2 で SELF_ISSUED_* を追加予定。 */
export type PointCardProviderType =
  | 'EXTERNAL'
  | 'SELF_ISSUED_STAMP'
  | 'SELF_ISSUED_BALANCE'

/** プロバイダー業種カテゴリ。フィルタとアイコン表示に使用。 */
export type PointCardCategory =
  | 'RETAIL'
  | 'CONVENIENCE'
  | 'FOOD'
  | 'TRANSPORT'
  | 'OTHER'

// =====================================================================
// Provider（運営マスタ）
// =====================================================================

/**
 * プロバイダー（運営マスタ）情報。
 * カード追加フォームのプリセットボタンと、所持カードのロゴ/色補強用。
 */
export interface PointCardProvider {
  /** UUIDv7 文字列 */
  id: string
  /** 内部識別子（例: `tokyu_point`）。i18n キー等に使う */
  code: string
  displayName: string
  category: PointCardCategory
  type: PointCardProviderType
  /** Phase 2 自店発行カード用。Phase 1 は常に null */
  organizationId: number | null
  logoUrl: string | null
  /** HEX (#RRGGBB)。WCAG AA コントラスト判定に使う */
  brandColor: string | null
  defaultBarcodeFormat: BarcodeFormat | null
  /** 「16 桁の数字」等の入力ヒント */
  cardNumberLengthHint: string | null
  /** 「本機能は ○○ の公式アプリではありません」等の法的注意書き */
  legalNotice: string | null
  isActive: boolean
}

// =====================================================================
// User Point Card
// =====================================================================

/**
 * カード一覧用の軽量 DTO。
 * 肩越し閲覧（shoulder surfing）リスク回避のため barcodeValue / nickname / memo は返さない。
 * provider 関連は flat フィールド（providerCode / providerDisplayName など）として展開される。
 */
export interface UserPointCardListItem {
  id: string
  providerId: string | null
  providerCode: string | null
  providerDisplayName: string | null
  providerBrandColor: string | null
  providerLogoUrl: string | null
  displayName: string
  last4: string | null
  barcodeFormat: BarcodeFormat
  favorite: boolean
  displayOrder: number
  lastUsedAt: string | null
  createdAt: string
}

/**
 * カード詳細 DTO。提示モードと編集モーダルで使う。
 * 暗号化フィールドを復号した状態で返却される（barcodeValue / nickname / memo）。
 * `providerMatched` はフロント側で「プロバイダー手動設定 UI」を出すかの判定に使う。
 */
export interface UserPointCardDetail {
  id: string
  providerId: string | null
  providerCode: string | null
  providerDisplayName: string | null
  providerBrandColor: string | null
  providerLogoUrl: string | null
  providerMatched: boolean
  displayName: string
  nickname: string | null
  barcodeValue: string
  barcodeFormat: BarcodeFormat
  last4: string | null
  memo: string | null
  favorite: boolean
  displayOrder: number
  lastUsedAt: string | null
  createdAt: string
  updatedAt: string
}

/**
 * カード追加リクエスト。
 * `providerId` はサーバー側 fuzzy match で自動解決されるため、クライアントからは送らない。
 */
export interface CreateUserPointCardRequest {
  displayName: string
  barcodeValue: string
  barcodeFormat: BarcodeFormat
  nickname?: string | null
  memo?: string | null
  favorite?: boolean
}

/**
 * カード更新（PATCH）リクエスト。全フィールド optional（null は既存値維持）。
 * `barcodeValue` / `barcodeFormat` はセキュリティ上の理由から更新不可（削除 → 再作成）。
 */
export interface UpdateUserPointCardRequest {
  displayName?: string
  nickname?: string | null
  memo?: string | null
  favorite?: boolean
  displayOrder?: number
}

// =====================================================================
// Group
// =====================================================================

/** グループ一覧の軽量 DTO（カード詳細を含まない）。 */
export interface PointCardGroupListItem {
  id: string
  name: string
  emoji: string | null
  displayOrder: number
  cardCount: number
  createdAt: string
  updatedAt: string
}

/**
 * グループ詳細内の 1 アイテム（カード復号値含む）。
 * バックエンドは 1 SQL の JPQL コンストラクタ式で取得し、N+1 を回避する。
 */
export interface PointCardGroupItem {
  cardId: string
  displayOrder: number
  displayName: string
  nickname: string | null
  barcodeValue: string
  barcodeFormat: BarcodeFormat
  last4: string | null
  providerId: string | null
  providerCode: string | null
  providerDisplayName: string | null
  providerBrandColor: string | null
  providerLogoUrl: string | null
  providerMatched: boolean
}

/** グループ詳細レスポンス。 */
export interface PointCardGroupDetail {
  id: string
  name: string
  emoji: string | null
  displayOrder: number
  items: PointCardGroupItem[]
  createdAt: string
  updatedAt: string
}

/** グループ作成リクエスト。cardIds は省略可（空グループ作成）。 */
export interface CreateGroupRequest {
  name: string
  emoji?: string | null
  cardIds?: string[]
}

/** グループ更新（PATCH）リクエスト。cardIds を指定した場合は既存アイテムを差し替える。 */
export interface UpdateGroupRequest {
  name?: string
  emoji?: string | null
  displayOrder?: number
  cardIds?: string[]
}

// =====================================================================
// User Settings
// =====================================================================

/** ユーザー設定（オプトイン状態・規約同意状態・WebAuthn 要求設定）。 */
export interface PointCardUserSettings {
  isEnabled: boolean
  /** ISO8601 文字列 */
  termsAcceptedAt: string | null
  /** 同意済みの規約バージョン（例: "v1.0.0"）。null = 未同意 */
  termsVersion: string | null
  requireBiometricOnShow: boolean
}

/**
 * ユーザー設定更新リクエスト。
 * `termsVersion` を送信した場合は `termsAcceptedAt` が現在時刻で更新される。
 */
export interface UpdateUserSettingsRequest {
  isEnabled?: boolean
  termsVersion?: string
  requireBiometricOnShow?: boolean
}

// =====================================================================
// 規約バージョン定数
// =====================================================================

/**
 * 現在の規約バージョン。ユーザーが同意した値を `termsAcceptedAt` と一緒に保存する。
 * 規約本文を変更する際は必ずバージョンを bump し、既存ユーザーには再同意を促す。
 */
export const CURRENT_TERMS_VERSION = 'v1.0.0'
