/**
 * F09.8.1 マイコルクボード ピン止めカード API レスポンス型。
 *
 * バックエンド DTO と 1:1 対応:
 *  - {@link com.mannschaft.app.corkboard.dto.PinnedCardResponse}
 *  - {@link com.mannschaft.app.corkboard.dto.PinnedCardReferenceResponse}
 *  - {@link com.mannschaft.app.corkboard.dto.PinnedCardListResponse}
 *
 * JSON はプロジェクト規約に従い camelCase。
 * Phase 4 では `GET /api/v1/users/me/corkboards/pinned-cards` の
 * レスポンス型として `WidgetMyCorkboard.vue` から参照される。
 */

/** カード参照先タイプ（バックエンド `referenceType` と同じ表記） */
export type PinnedCardReferenceType =
  | 'TIMELINE_POST'
  | 'BULLETIN_THREAD'
  | 'BLOG_POST'
  | 'CHAT_MESSAGE'
  | 'FILE'
  | 'TEAM'
  | 'ORGANIZATION'
  | 'EVENT'
  | 'DOCUMENT'
  | 'URL'

/** カード本体タイプ（既存 `corkboard_cards.card_type`） */
export type PinnedCardType = 'REFERENCE' | 'MEMO' | 'URL' | 'SECTION_HEADER'

export interface PinnedCardReference {
  /** 参照タイプ。バックエンドが未対応 type を返した場合は単に文字列扱い */
  type: PinnedCardReferenceType | string
  /** 参照先 ID。URL カードでは null */
  id: number | null
  /** 参照スナップショットのタイトル（論理削除時のフォールバック表示にも使う） */
  snapshotTitle?: string | null
  /** 参照スナップショットの抜粋 */
  snapshotExcerpt?: string | null
  /** 閲覧権限あり/なし。false 時はグレーアウト表示・ナビ無効 */
  isAccessible: boolean
  /** 参照先が論理削除済みなら true */
  isDeleted: boolean
  /** ナビゲート先（相対パス）。閲覧権限なし時は null。URL カードは絶対 URL */
  navigateTo: string | null
  /** URL カード専用: 元 URL */
  url?: string | null
  /** URL カード専用: OGP タイトル */
  ogTitle?: string | null
  /** URL カード専用: OGP 画像 URL */
  ogImageUrl?: string | null
}

export interface PinnedCardItem {
  cardId: number
  corkboardId: number
  /** 所属コルクボード名。匿名表示の場合 null */
  corkboardName: string | null
  cardType: PinnedCardType
  /** カード色ラベル（YELLOW / BLUE / GREEN / RED 等） */
  colorLabel: string | null
  title: string | null
  body: string | null
  /** ユーザーが付けたメモ（ピン止めに紐づく短文） */
  userNote: string | null
  /** ピン止め日時（ISO 8601 文字列） */
  pinnedAt: string
  /** 参照先メタ。MEMO / SECTION_HEADER 等の純メモカードでは null */
  reference: PinnedCardReference | null
}

export interface PinnedCardListResponse {
  items: PinnedCardItem[]
  /** 次ページ取得用の不透明カーソル文字列。次ページが無い場合は null */
  nextCursor: string | null
  /** 当該ユーザーのピン止め済みカード総数（ページネーションに依存しない） */
  totalCount: number
}
