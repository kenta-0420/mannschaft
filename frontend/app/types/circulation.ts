export type CirculationStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'

/**
 * 回覧文書一覧の 1 件分（BE `DocumentResponse` に一致）。
 *
 * <p>scoped 一覧 EP `GET /api/v1/teams/{id}/circulations`（および organizations 版）が返す
 * フラットな DTO 形。{@link CirculationResponse}（ネストした createdBy オブジェクトを持つ手書き契約型）
 * とは別物で、こちらが実 BE の応答に一致する。</p>
 */
export interface CirculationDocumentListItem {
  id: number
  scopeType: string
  scopeId: number
  title: string
  body: string | null
  status: CirculationStatus
  createdBy: number
  createdByName: string | null
  dueDate: string | null
  stampDisplayStyle: string
  totalRecipientCount: number
  stampedCount: number
  createdAt: string
  updatedAt: string
}

/**
 * 回覧受信者（押印状況）の 1 件分（BE `RecipientResponse` に一致）。
 *
 * <p>EP `GET /api/v1/circulations/{documentId}/recipients` が返す実 BE 応答の形。
 * 以前の手書き型は `displayName` / `stampStatus` / `comment` 等を持っていたが、
 * 実 BE の `RecipientResponse`（`backend/.../circulation/dto/RecipientResponse.java`）は
 * それらを返さない（表示名付きの押印状況は ADMIN 専用の `/status` EP 側にある）。
 * 詳細モーダルは表示名解決をメンバー一覧 EP で補う。</p>
 */
export interface CirculationRecipient {
  id: number
  documentId: number
  userId: number
  sortOrder: number | null
  /** 押印状態（PENDING / STAMPED / SKIPPED / REJECTED）。 */
  status: 'PENDING' | 'STAMPED' | 'SKIPPED' | 'REJECTED'
  stampedAt: string | null
  sealId: number | null
  sealVariant: string | null
  tiltAngle: number | null
  isFlipped: boolean | null
  createdAt: string
  updatedAt: string
}

/**
 * 回覧添付ファイルの 1 件分（BE `AttachmentResponse` に一致）。
 *
 * <p>EP `GET /api/v1/circulations/{documentId}/attachments` が返す実 BE 応答の形。
 * `fileName` / `url` ではなく `originalFilename` / `fileKey` を返す。</p>
 */
export interface CirculationAttachment {
  id: number
  documentId: number
  fileKey: string
  originalFilename: string | null
  fileSize: number | null
  mimeType: string | null
  createdAt: string
}

export interface CirculationResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  title: string
  body: string | null
  status: CirculationStatus
  createdBy: { id: number; displayName: string; avatarUrl: string | null }
  stampDisplayStyle: string
  deadline: string | null
  recipientCount: number
  stampedCount: number
  attachments: CirculationAttachment[]
  createdAt: string
  updatedAt: string
}

/**
 * 回覧文書詳細（BE `DocumentResponse` に一致・フラット）。
 *
 * <p>scoped 詳細 EP `GET /api/v1/teams/{teamId}/circulations/{documentId}`（および
 * organizations 版）が返す本体。{@link CirculationDocumentListItem} の全フィールドに加え、
 * 詳細表示で使う `priority` / `circulationMode` / `completedAt` / `attachmentCount` /
 * `commentCount` / `reminder*` / `sequentialCount` を持つ。受信者一覧は本体に含まれず、
 * 別 EP `getRecipients` で取得する。</p>
 */
export interface CirculationDocumentDetail extends CirculationDocumentListItem {
  priority: string | null
  circulationMode: string | null
  completedAt: string | null
  attachmentCount: number | null
  commentCount: number | null
  reminderEnabled: boolean | null
  reminderIntervalHours: number | null
  sequentialCount: number | null
}

/**
 * 回覧文書詳細 EP のレスポンスエンベロープ（`ApiResponse<DocumentResponse>`）。
 *
 * <p>以前は `CirculationResponse`（ネストした createdBy）に `recipients` を足した形だったが、
 * 実 BE は受信者を含まないフラットな `DocumentResponse` を返す。受信者は別 EP で取る。</p>
 */
export interface CirculationDetailResponse {
  data: CirculationDocumentDetail
}

export interface CreateCirculationRequest {
  title: string
  body?: string
  stampDisplayStyle?: string
  deadline?: string
  recipientGroups: Array<{
    groupOrder: number
    userIds: number[]
  }>
}

export interface UpdateCirculationRequest {
  title?: string
  body?: string
  priority?: string
  dueDate?: string
  reminderEnabled?: boolean
  reminderIntervalHours?: number
  stampDisplayStyle?: string
}

/**
 * 回覧コメントの 1 件分（BE `CommentResponse` に一致）。
 *
 * <p>EP `GET /api/v1/circulations/{documentId}/comments` が返す実 BE 応答の形。
 * BE の `CommentResponse` は `displayName` / `avatarUrl` を返さないため、
 * 表示名はメンバー一覧から補完する（詳細モーダル側で解決）。</p>
 */
export interface CirculationComment {
  id: number
  documentId: number
  userId: number
  body: string
  createdAt: string
  updatedAt: string
}

/**
 * 押印リクエスト（BE `circulation/dto/StampRequest.java` に一致）。
 *
 * <p>EP `POST /api/v1/circulations/{documentId}/stamp` のリクエストボディ。
 * 以前の手書き型は seal ドメインの StampRequest（`targetType` / `targetId` /
 * `stampDocumentHash`）と取り違えていたため、回覧ドメインの実 DTO に是正した。
 * `docs/openapi.json` の `StampRequest` スキーマは名前衝突で seal ドメイン側が
 * 畳み込まれており stale。BE の Java ソースが正準。</p>
 */
export interface CirculationStampRequest {
  /** 使用する印鑑 ID（必須）。 */
  sealId: number
  /** 印鑑のバリアント（姓 / フルネーム / 名）。省略可。 */
  sealVariant?: string
  /** 押印の傾き角度（度）。省略可。 */
  tiltAngle?: number
  /** 反転フラグ。省略可。 */
  isFlipped?: boolean
}

export interface CirculationStatsResponse {
  totalDocuments: number
  inProgress: number
  completed: number
  overdueCount: number
}

export interface AddRecipientsRequest {
  recipients: Array<{
    userId: number
    groupOrder?: number
  }>
}

export interface CreateAttachmentRequest {
  attachmentType: string
  fileKey?: string
  originalFilename?: string
  fileSize?: number
  mimeType?: string
}

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload リクエスト型。
 * サーバー側で新統一パス命名規則に従った fileKey を生成してもらう。
 */
export interface CirculationAttachmentPresignRequest {
  fileName: string
  contentType: string
  fileSize: number
}

/**
 * F13 Phase 5-a: 回覧板添付ファイル presign-upload レスポンス型。
 * uploadUrl を使って R2 に直接 PUT し、完了後に fileKey を addAttachment API に渡す。
 */
export interface CirculationAttachmentPresignResponse {
  uploadUrl: string
  fileKey: string
  expiresInSeconds: number
}
