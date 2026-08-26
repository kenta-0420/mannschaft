export interface ActivityRecordResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  templateId: number | null
  templateName: string | null
  title: string
  activityDate: string
  location: string | null
  description: string | null
  participants: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  participantCount: number
  customFields: Array<{ fieldId: number; fieldName: string; fieldType: string; value: string | null }>
  isPublic: boolean
  /** 活動記録のステータス。BE 追加（隊乙 #2143）で DRAFT / PUBLISHED の2値 */
  status?: 'DRAFT' | 'PUBLISHED'
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

/**
 * 活動記録のカスタムフィールド型。
 * バックエンド {@code com.mannschaft.app.activity.FieldType} と一致させる。
 */
export type ActivityFieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'DATETIME' | 'SELECT' | 'CHECKBOX' | 'TEXTAREA'

/**
 * 活動テンプレートのフィールド定義。
 * バックエンド {@code ActivityTemplateResponse.TemplateFieldResponse} に準拠する。
 * - {@code fieldKey}: 活動記録作成時の {@code fieldValues} のキーになる識別子（fieldName/id ではない）
 * - {@code fieldLabel}: 画面表示用ラベル
 * - {@code optionsJson}: SELECT 型の選択肢を表す JSON 文字列（例: {@code ["A","B"]}）
 */
export interface ActivityTemplateField {
  id: number
  fieldKey: string
  fieldLabel: string
  fieldType: ActivityFieldType
  isRequired: boolean
  optionsJson: string | null
  placeholder: string | null
  unit: string | null
  isAggregatable: boolean
  sortOrder: number
}

export interface ActivityTemplate {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  description: string | null
  icon: string | null
  color: string | null
  isParticipantRequired: boolean
  defaultVisibility: 'PUBLIC' | 'MEMBERS_ONLY' | null
  sortOrder: number
  fields: ActivityTemplateField[]
  createdAt: string
  updatedAt: string
}

/**
 * 活動記録作成リクエストボディ（手書き）。
 * バックエンド {@code CreateActivityRequest} の body 部分に対応する。
 * scope_type / scope_id はクエリパラメータで送るため body には含めない。
 * NOTE: 作成 API には {@code location} フィールドは存在しない（一覧で返る location は送らない）。
 * visibility は PUBLIC / MEMBERS_ONLY の2値のみ（PRIVATE は無い・未指定時 BE 既定 MEMBERS_ONLY）。
 */
export interface CreateActivityRequestBody {
  templateId: number
  title: string
  /** "yyyy-MM-dd" */
  activityDate: string
  description?: string
  /** "HH:mm" */
  activityTimeStart?: string
  /** "HH:mm" */
  activityTimeEnd?: string
  visibility?: 'PUBLIC' | 'MEMBERS_ONLY'
  /** キーはテンプレの fieldKey。値は fieldType 別の素の型 */
  fieldValues?: Record<string, string | number | boolean>
  participantUserIds?: number[]
  fileIds?: number[]
  scheduleId?: number
  postToTimeline?: boolean
}

export interface ActivityComment {
  id: number
  activityId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

export interface ActivityStats {
  totalActivities: number
  totalParticipants: number
  averageParticipants: number
  monthlyBreakdown: Array<{ month: string; count: number }>
}

/**
 * 公開スコープ参照（チーム / 組織）。
 *
 * BE {@code PublicScopeRef} に対応する。公開経路ではスコープの生 ID を直接返さず、
 * 種別・ID・表示名をまとめたこの参照経由でのみ露出する。
 */
export interface PublicScopeRef {
  scopeType: string
  scopeId: number
  scopeName: string
}

/**
 * 公開活動記録 API レスポンス（F06.4・認証不要）。
 *
 * 対象エンドポイント:
 * - GET /api/v1/public/activities/{id}
 * - GET /api/v1/public/teams/{teamId}/activities/{id}
 * - GET /api/v1/public/organizations/{orgId}/activities/{id}
 *
 * BE の公開専用 DTO {@code PublicActivityDetail}（および一覧用 {@code PublicActivitySummary}）に
 * 1:1 で対応する。匿名公開のため軍議で御裁可された **8 項目のみ** を返し、
 * `location` / `fieldValues` / `attachments` / `createdBy` / `visibility` / `status` /
 * `templateId` / `venueId` / `scheduleId` / `updatedAt` は **禁則フィールドとして一切返らない**。
 * ここに項目を足す前に、必ず BE 側 DTO と「未認証の誰にでも見せてよいか」を再審議すること。
 *
 * NOTE: BE は `@JsonInclude(NON_NULL)` を付けないため、値が未設定でも
 * キー自体は常に存在し `null` が入る（契約テスト ActivityPublicContractIT AC-23 / AC-24）。
 */
export interface PublicActivityResponse {
  id: number
  title: string
  activityDate: string
  /** 開始時刻（未設定なら null） */
  activityTimeStart: string | null
  /** 終了時刻（未設定なら null） */
  activityTimeEnd: string | null
  /** 説明（未設定なら null） */
  description: string | null
  scopeRef: PublicScopeRef
  createdAt: string
}

/**
 * 公開活動記録 一覧レスポンス（BE {@code PublicActivitySummary} に対応）。
 *
 * 対象エンドポイント:
 * - GET /api/v1/public/teams/{teamId}/activities
 * - GET /api/v1/public/organizations/{orgId}/activities
 *
 * 現状は詳細と同一の 8 項目だが、BE 側で別型に分かれている（一覧だけ項目を削る／
 * 詳細だけ足す変更が互いに波及しないようにするため）ので FE も別名で扱う。
 * **一覧は詳細より広い項目を持ってはならない。**
 */
export type PublicActivitySummaryResponse = PublicActivityResponse
