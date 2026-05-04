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

/** 参照カードの参照先種別 */
export type CorkboardReferenceType =
  | 'CHAT_MESSAGE'
  | 'TIMELINE_POST'
  | 'BULLETIN_THREAD'
  | 'BLOG_POST'
  | 'FILE'

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
