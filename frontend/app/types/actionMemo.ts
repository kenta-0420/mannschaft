/**
 * F02.5 行動メモ機能の型定義。
 *
 * 設計書 §4 に基づき、Backend は JSON で一部スネークケースを使うが、
 * フロント側ではキャメルケースに正規化したものを利用する。
 * 変換は {@link useActionMemoApi} 内で行う。
 */

// === Mood ===

/** 5択リテラルユニオン。NULL は「未選択」を表す */
export type Mood = 'GREAT' | 'GOOD' | 'OK' | 'TIRED' | 'BAD'

// === Category (Phase 3) ===

/** メモカテゴリ。WORK のみチーム投稿対象となる */
export type ActionMemoCategory = 'WORK' | 'PRIVATE' | 'OTHER'

// === OrgVisibility (Phase 4-α) ===

/** 組織スコープ公開範囲。organization_id が設定された場合のみ有効 */
export type OrgVisibility = 'TEAM_ONLY' | 'ORG_WIDE'

// === Tag ===

/**
 * メモに紐付くタグサマリ（埋め込み用）。
 * Phase 4 で本格的な CRUD UI を作るが、Phase 1 ではメモ表示時の参照型として用意する。
 */
export interface ActionMemoTag {
  id: number
  name: string
  /** HEX カラー。NULL = デフォルト色 */
  color: string | null
  /** 論理削除済みタグかどうか。過去メモ表示時にバッジ色を変える */
  deleted: boolean
}

// === Memo ===

/**
 * 行動メモ本体。
 * Backend の {@code action_memos} テーブルに対応。
 */
export interface ActionMemo {
  id: number
  /** ISO 日付（YYYY-MM-DD） */
  memoDate: string
  content: string
  /** 設定 OFF のユーザーは常に null */
  mood: Mood | null
  relatedTodoId: number | null
  /** 終業投稿がまだなら null */
  timelinePostId: number | null
  tags: ActionMemoTag[]
  /** ISO LocalDateTime（例 "2026-04-09T08:32:14"） */
  createdAt: string
  updatedAt?: string
  /** Phase 3: メモカテゴリ（デフォルト OTHER）*/
  category: ActionMemoCategory
  /** Phase 3: 実績時間（分単位）。未入力時は null */
  durationMinutes: number | null
  /** Phase 3: 進捗率（0.00〜100.00）。relatedTodoId が null の場合は null */
  progressRate: number | null
  /** Phase 3: 保存時に relatedTodo を完了にするか否か */
  completesTodo: boolean
  /** Phase 3: チーム投稿先の teamId。未投稿または WORK 以外は null */
  postedTeamId: number | null
  /** Phase 4-α: 組織スコープ投稿先の組織 ID。未設定時は null */
  organizationId: number | null
  /** Phase 4-α: 組織公開範囲。organizationId が null の場合は null */
  orgVisibility: OrgVisibility | null
}

// === Settings ===

/**
 * ユーザー個別の行動メモ設定。
 * レコード未作成のユーザーはサーバー側でデフォルト値が返る。
 */
export interface ActionMemoSettings {
  moodEnabled: boolean
  /** Phase 3: WORKメモ作成時のデフォルト投稿先チーム ID */
  defaultPostTeamId: number | null
  /** Phase 3: デフォルトカテゴリ */
  defaultCategory: ActionMemoCategory
}

// === Available teams (Phase 3) ===

/**
 * チーム投稿先候補。
 * {@code GET /api/v1/action-memos/available-teams} で取得する。
 */
export interface AvailableTeam {
  id: number
  name: string
  /** このチームがデフォルト投稿先として設定されているか */
  isDefault: boolean
}

// === Request payloads ===

export interface CreateActionMemoPayload {
  content: string
  /** 省略時はサーバー側で JST 今日が自動セットされる */
  memoDate?: string
  mood?: Mood | null
  relatedTodoId?: number | null
  tagIds?: number[]
  /** Phase 3 */
  category?: ActionMemoCategory
  durationMinutes?: number | null
  progressRate?: number | null
  completesTodo?: boolean
  /** Phase 4-α */
  organizationId?: number | null
  orgVisibility?: OrgVisibility | null
}

export interface UpdateActionMemoPayload {
  content?: string
  memoDate?: string
  mood?: Mood | null
  relatedTodoId?: number | null
  tagIds?: number[]
  /** Phase 3 */
  category?: ActionMemoCategory
  durationMinutes?: number | null
  progressRate?: number | null
  completesTodo?: boolean
  /** Phase 4-α: 0 を送るとクリア */
  organizationId?: number | null
  orgVisibility?: OrgVisibility | null
}

// === Audit logs (Phase 4-α) ===

/** メモに紐付く監査ログ項目（折りたたみUI用） */
export interface MemoAuditLog {
  id: number
  eventType: string
  userId: number | null
  metadata: string | null
  createdAt: string
}

// === Publish to team (Phase 3) ===

/**
 * 個別メモをチームタイムラインに投稿するリクエストペイロード。
 * {@code POST /api/v1/action-memos/{memoId}/publish-to-team}
 */
export interface PublishToTeamPayload {
  teamId?: number | null
  extraComment?: string | null
}

/**
 * 今日の WORK メモを一括チーム投稿するリクエストペイロード。
 * {@code POST /api/v1/action-memos/publish-daily-to-team}
 */
export interface PublishDailyToTeamPayload {
  teamId?: number | null
}

// === List response ===

export interface ActionMemoListResponse {
  data: ActionMemo[]
  /** 次ページがある場合のカーソル。最終ページなら null */
  nextCursor: string | null
}

// === List query params ===

export interface ListActionMemoParams {
  /** 単日指定（YYYY-MM-DD）。`from`/`to` と排他 */
  date?: string
  /** 期間指定（YYYY-MM-DD）開始 */
  from?: string
  /** 期間指定（YYYY-MM-DD）終了 */
  to?: string
  tagId?: number
  cursor?: string
  /** デフォルト 50、最大 200 */
  limit?: number
}

// === Publish daily (Phase 2) ===

/**
 * {@code POST /api/v1/action-memos/publish-daily} のリクエストペイロード。
 *
 * <p>設計書 §4: 両フィールドとも任意。{@code memoDate} 省略時はサーバー側で
 * JST の今日が採用される。{@code extraComment} は最大 1,000 文字でまとめ本文末尾に
 * 追記される任意のひと言。</p>
 */
export interface PublishDailyPayload {
  /** ISO 日付（YYYY-MM-DD）。省略時はサーバー側で JST 今日 */
  memoDate?: string
  /** まとめ本文末尾に追記される任意のひと言（最大 1,000 文字） */
  extraComment?: string
}

/**
 * {@code POST /api/v1/action-memos/publish-daily} の成功レスポンス。
 */
export interface PublishDailyResponse {
  /** 新規作成された timeline_posts.id */
  timelinePostId: number
  /** 本文に含めたメモの件数 */
  memoCount: number
  /** 集計対象日（YYYY-MM-DD） */
  memoDate: string
}

// === Offline queue (Phase 2) ===

/**
 * IndexedDB に退避されるオフラインキュー項目。
 *
 * <p>設計書 §4.x「オフライン対応」に基づき、{@code navigator.onLine === false} の状態で
 * 発行された createMemo をローカルに保存し、オンライン復帰時または手動同期ボタン操作時に
 * 順次送信する。{@code tempId} は楽観的 UI で一覧に挿入した仮 ID と対応し、同期成功後の
 * 本物の ID への置き換えに利用する。</p>
 *
 * <p>Phase 2 では {@code @vite-pwa/nuxt} 等の Service Worker 機構が導入されていないため、
 * {@code online} イベント + 手動同期ボタンで縮退運用する（F11.1 PWA 連携は将来実装）。</p>
 */
export interface OfflineQueuedMemo {
  /** 自動採番のキュー項目 ID（IndexedDB 側の primary key） */
  queueId?: number
  /** store で楽観的 UI に使っている仮 ID（負数） */
  tempId: number
  /** 送信予定のペイロード */
  payload: CreateActionMemoPayload
  /** キューに積んだ時刻（ISO） */
  enqueuedAt: string
}

// === Weekly Summary (Phase 3) ===

/**
 * 週次まとめの要約型。
 *
 * <p>Backend Phase 3 の {@code ActionMemoWeeklySummaryService} が毎週日曜 21:00 JST に
 * 生成する非公開ブログ記事を、F06.1 BlogPost API から取得しフロント用に正規化したもの。
 * 設計書 §11 #11 の確定判断「新規 API は作らない」に従い、{@code GET /api/v1/blog/posts}
 * の {@code visibility=PRIVATE} フィルタ + タイトルプレフィックス「週次ふりかえり: 」の
 * クライアント側フィルタで抽出する。</p>
 */
export interface WeeklySummary {
  id: number
  title: string
  body: string
  publishedAt: string | null
  /** タイトルから抽出した対象期間 */
  period: {
    from: string
    to: string
  }
}

/**
 * 週次まとめ一覧レスポンス。
 *
 * <p>F06.1 BlogPost API の {@code PagedResponse} をフロント向けに変換した型。
 * ページベースのページネーション（page / totalPages）を採用する。</p>
 */
export interface WeeklySummaryListResponse {
  data: WeeklySummary[]
  page: number
  totalPages: number
}

// === Tag payloads (Phase 4) ===

export interface CreateTagPayload {
  name: string
  color?: string
}

export interface UpdateTagPayload {
  name?: string
  color?: string
}

// === Mood stats (Phase 4) ===

/**
 * 気分集計レスポンス。
 *
 * <p>Backend の {@code MoodStatsResponse} に対応。
 * {@code distribution} は {@code Mood} をキーとした件数マップ。</p>
 */
export interface MoodStatsResponse {
  total: number
  distribution: Partial<Record<Mood, number>>
}

// === Error helpers ===

/**
 * 429 レートリミット応答に付与されるカスタムエラー。
 * `Retry-After` ヘッダー（秒）を呼び出し側に伝えるために使う。
 */
export interface ActionMemoRateLimitError extends Error {
  status: 429
  retryAfterSeconds: number | null
}
