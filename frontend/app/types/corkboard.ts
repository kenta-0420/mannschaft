/**
 * F09.8 コルクボード フロントエンド型定義。
 *
 * 既存 (`CorkboardResponse` / `CorkboardCard` / `CorkboardSection`) は Phase 5 までに
 * 作られた古い予想モデルで、`/corkboard` 一覧ページが依存しているため互換維持目的で残置する。
 *
 * Phase B 以降は Phase A バックエンド DTO に厳密整合した下記の型を使うこと:
 *  - {@link CorkboardScope}
 *  - {@link CorkboardSummary}
 *  - {@link CorkboardCardDetail}
 *  - {@link CorkboardGroupDetail}
 *  - {@link CorkboardDetail}
 *
 * バックエンド DTO との 1:1 対応:
 *  - {@link com.mannschaft.app.corkboard.dto.CorkboardResponse} → {@link CorkboardSummary}
 *  - {@link com.mannschaft.app.corkboard.dto.CorkboardDetailResponse} → {@link CorkboardDetail}
 *  - {@link com.mannschaft.app.corkboard.dto.CorkboardCardResponse} → {@link CorkboardCardDetail}
 *  - {@link com.mannschaft.app.corkboard.dto.CorkboardGroupResponse} → {@link CorkboardGroupDetail}
 */

// ===== 旧型（Phase 5 互換のため残置。新規実装では使わない） =====

export type CardType = 'NOTE' | 'IMAGE' | 'LINK' | 'CHECKLIST' | 'FILE'

/**
 * バックエンド `CorkboardResponse` (Java) に対応する TS 型。
 * F09.8 Phase A2: 旧フィールド (`title` / `description` / `cardCount` / `isArchived` / `sections`) は
 * バックエンド実体に存在しないため除去し、実フィールド (`name` / `backgroundStyle` / `editPolicy` /
 * `isDefault` / `version` / `ownerId`) に揃える。
 */
export interface CorkboardResponse {
  id: number
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  scopeId: number | null
  ownerId: number | null
  name: string
  backgroundStyle: string | null
  editPolicy: string | null
  isDefault: boolean
  version: number | null
  createdAt: string
  updatedAt: string | null
}

export interface CorkboardCard {
  id: number
  boardId: number
  sectionId: number | null
  cardType: CardType
  title: string
  content: string | null
  color: string | null
  positionX: number
  positionY: number
  width: number
  height: number
  isPinned: boolean
  isArchived: boolean
  imageUrl: string | null
  linkUrl: string | null
  checklistItems: Array<{ text: string; checked: boolean }> | null
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

// ===== 新型（Phase A バックエンド DTO 整合） =====

/** ボードのスコープ種別 */
export type CorkboardScope = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

/** ボードの編集ポリシー */
export type CorkboardEditPolicy = 'ADMIN_ONLY' | 'ALL_MEMBERS'

/** ボードの背景スタイル */
export type CorkboardBackgroundStyle = 'CORK' | 'WHITE' | 'DARK'

/** カード本体の種別（バックエンド `card_type` と同一表記） */
export type CorkboardCardType = 'REFERENCE' | 'MEMO' | 'URL' | 'SECTION_HEADER'

/**
 * 参照カードの参照先種別。
 *
 * 設計書 §4 で公式に列挙されているのは `CHAT_MESSAGE` / `TIMELINE_POST` / `BULLETIN_THREAD`
 * / `BLOG_POST` / `FILE` の 5 種だが、Phase C のフォームでは将来拡張枠として
 * `TEAM` / `ORGANIZATION` / `EVENT` / `DOCUMENT` / `URL` も選べるようにする
 * （バックエンドの `referenceType` は `Size(max=30)` の自由文字列で受ける）。
 */
export type CorkboardReferenceType =
  | 'CHAT_MESSAGE'
  | 'TIMELINE_POST'
  | 'BULLETIN_THREAD'
  | 'BLOG_POST'
  | 'FILE'
  | 'TEAM'
  | 'ORGANIZATION'
  | 'EVENT'
  | 'DOCUMENT'
  | 'URL'

/** カードのカラーラベル（設計書 §3 corkboard_cards.color） */
export type CorkboardColor = 'WHITE' | 'YELLOW' | 'RED' | 'BLUE' | 'GREEN' | 'PURPLE' | 'GRAY'

/** カードのサイズプリセット */
export type CorkboardCardSize = 'SMALL' | 'MEDIUM' | 'LARGE'

/**
 * ボード一覧レスポンス（{@link com.mannschaft.app.corkboard.dto.CorkboardResponse} と 1:1）。
 */
export interface CorkboardSummary {
  id: number
  scopeType: CorkboardScope
  scopeId: number | null
  ownerId: number | null
  name: string
  backgroundStyle: CorkboardBackgroundStyle | string
  editPolicy: CorkboardEditPolicy | string
  isDefault: boolean
  version: number
  createdAt: string
  updatedAt: string
}

/**
 * カード詳細（{@link com.mannschaft.app.corkboard.dto.CorkboardCardResponse} と 1:1）。
 */
export interface CorkboardCardDetail {
  id: number
  corkboardId: number
  cardType: CorkboardCardType | string
  referenceType: CorkboardReferenceType | string | null
  referenceId: number | null
  contentSnapshot: string | null
  title: string | null
  body: string | null
  url: string | null
  ogTitle: string | null
  ogImageUrl: string | null
  ogDescription: string | null
  colorLabel: CorkboardColor | string | null
  cardSize: CorkboardCardSize | string | null
  positionX: number
  positionY: number
  zIndex: number | null
  userNote: string | null
  autoArchiveAt: string | null
  isArchived: boolean
  isPinned: boolean
  pinnedAt: string | null
  /** 参照先削除済みフラグ（REFERENCE のみ。デッドリファレンス検知バッチが設定） */
  isRefDeleted: boolean
  createdBy: number | null
  createdAt: string
  updatedAt: string
}

/**
 * セクション詳細（{@link com.mannschaft.app.corkboard.dto.CorkboardGroupResponse} と 1:1）。
 */
export interface CorkboardGroupDetail {
  id: number
  corkboardId: number
  name: string
  isCollapsed: boolean
  positionX: number
  positionY: number
  width: number
  height: number
  displayOrder: number
  createdAt: string
  updatedAt: string
}

/**
 * カード作成リクエスト（{@link com.mannschaft.app.corkboard.dto.CreateCardRequest} と 1:1）。
 *
 * - JSON は camelCase（バックエンド DTO と完全整合）。
 * - `cardType` は必須。`REFERENCE` の場合は `referenceType` + `referenceId` 必須、
 *   `URL` の場合は `url` 必須、`MEMO` / `SECTION_HEADER` の場合は `title` または `body` を使う。
 * - `positionX` / `positionY` はバックエンド側で 0 デフォルト扱い。フロントから明示送信する。
 */
export interface CreateCardRequest {
  cardType: CorkboardCardType
  referenceType?: CorkboardReferenceType | string | null
  referenceId?: number | null
  title?: string | null
  body?: string | null
  url?: string | null
  colorLabel?: CorkboardColor | null
  cardSize?: CorkboardCardSize | null
  positionX?: number | null
  positionY?: number | null
  zIndex?: number | null
  userNote?: string | null
  /** ISO 8601 (`yyyy-MM-ddTHH:mm:ss`) 形式の自動アーカイブ日時 */
  autoArchiveAt?: string | null
}

/**
 * カード更新リクエスト（{@link com.mannschaft.app.corkboard.dto.UpdateCardRequest} と 1:1）。
 *
 * カード種別 (`cardType`) と参照先 (`referenceType` / `referenceId`) は変更不可。
 * 必要なフィールドのみを送る部分更新を許容する（null は明示クリア、undefined は無変更）。
 */
export interface UpdateCardRequest {
  title?: string | null
  body?: string | null
  url?: string | null
  colorLabel?: CorkboardColor | null
  cardSize?: CorkboardCardSize | null
  positionX?: number | null
  positionY?: number | null
  zIndex?: number | null
  userNote?: string | null
  autoArchiveAt?: string | null
}

/**
 * セクション作成リクエスト（{@link com.mannschaft.app.corkboard.dto.CreateGroupRequest} と 1:1）。
 *
 * - `name` のみ必須（最大 100 文字）。
 * - `isCollapsed` / 位置・サイズ / `displayOrder` は任意（バックエンド側でデフォルト値補完）。
 *
 * 注: バックエンド DTO に `description` フィールドは存在しないため Phase E では扱わない。
 * 将来 DDL 追加時に併せて追加する。
 */
export interface CreateGroupRequest {
  name: string
  isCollapsed?: boolean | null
  positionX?: number | null
  positionY?: number | null
  width?: number | null
  height?: number | null
  displayOrder?: number | null
}

/**
 * セクション更新リクエスト（{@link com.mannschaft.app.corkboard.dto.UpdateGroupRequest} と 1:1）。
 *
 * バックエンド DTO のフィールドは {@link CreateGroupRequest} と同一。
 */
export interface UpdateGroupRequest {
  name: string
  isCollapsed?: boolean | null
  positionX?: number | null
  positionY?: number | null
  width?: number | null
  height?: number | null
  displayOrder?: number | null
}

/**
 * ボード詳細レスポンス（{@link com.mannschaft.app.corkboard.dto.CorkboardDetailResponse} と 1:1）。
 */
export interface CorkboardDetail {
  id: number
  scopeType: CorkboardScope
  scopeId: number | null
  ownerId: number | null
  name: string
  backgroundStyle: CorkboardBackgroundStyle | string
  editPolicy: CorkboardEditPolicy | string
  isDefault: boolean
  version: number
  cards: CorkboardCardDetail[]
  groups: CorkboardGroupDetail[]
  createdAt: string
  updatedAt: string
}
