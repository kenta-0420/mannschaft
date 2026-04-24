/**
 * F13.1 スキマバイト（求人マッチング）の型定義。
 *
 * <p>Backend の DTO / Entity / Enum を真として 1:1 でミラーする（手動管理）。
 * 対応 Backend パッケージ: {@code com.mannschaft.app.jobmatching}</p>
 *
 * <p>Phase 13.1.1 MVP 対応。後続 Phase で QR チェックイン・エスクロー・評価等を追加予定。</p>
 */

// ==================================================
// Enum（BE と完全一致）
// ==================================================

/** 求人投稿ステータス。BE: {@code JobPostingStatus} */
export type JobPostingStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED'

/** 求人応募ステータス。BE: {@code JobApplicationStatus} */
export type JobApplicationStatus = 'APPLIED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'

/**
 * 求人契約ステータス。BE: {@code JobContractStatus}。
 * MVP では MATCHED / COMPLETION_REPORTED / COMPLETED / CANCELLED を主に使用する。
 */
export type JobContractStatus =
  | 'MATCHED'
  | 'CHECKED_IN'
  | 'IN_PROGRESS'
  | 'CHECKED_OUT'
  | 'TIME_CONFIRMED'
  | 'COMPLETION_REPORTED'
  | 'COMPLETED'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'PAID'
  | 'CANCELLED'
  | 'DISPUTED'

/**
 * 求人カテゴリ。BE Entity は {@code String} として保持するため TS 側は
 * 代表値のユニオン + 任意の文字列としても受け入れる形にする（MVP）。
 */
export type JobCategory = string

/** 業務場所種別。BE: {@code WorkLocationType} */
export type WorkLocationType = 'ONSITE' | 'ONLINE' | 'HYBRID'

/** 報酬タイプ。BE: {@code RewardType} */
export type RewardType = 'HOURLY' | 'DAILY' | 'LUMP_SUM'

/**
 * 求人公開範囲。BE: {@code VisibilityScope}。
 * MVP の UI では TEAM_MEMBERS / TEAM_MEMBERS_SUPPORTERS のみ選択可とする。
 */
export type VisibilityScope =
  | 'TEAM_MEMBERS'
  | 'TEAM_MEMBERS_SUPPORTERS'
  | 'JOBBER_INTERNAL'
  | 'JOBBER_PUBLIC_BOARD'
  | 'ORGANIZATION_SCOPE'
  | 'CUSTOM_TEMPLATE'

/** MVP で UI に提示する公開範囲（第三版以降のスコープは除外）。 */
export const MVP_VISIBILITY_SCOPES: VisibilityScope[] = [
  'TEAM_MEMBERS',
  'TEAM_MEMBERS_SUPPORTERS',
]

// ==================================================
// Request DTO（FE → BE）
// ==================================================

/** BE: {@code CreateJobPostingRequest} */
export interface CreateJobPostingRequest {
  teamId: number
  title: string
  description: string
  category?: string | null
  workLocationType: WorkLocationType
  workAddress?: string | null
  /** ISO8601 (LocalDateTime)。未来日時。 */
  workStartAt: string
  /** ISO8601 (LocalDateTime)。workStartAt 以降。 */
  workEndAt: string
  rewardType: RewardType
  /** 500 〜 1,000,000 円 */
  baseRewardJpy: number
  /** 1 以上 */
  capacity: number
  /** ISO8601 (LocalDateTime)。未来・workStartAt 以前。 */
  applicationDeadlineAt: string
  visibilityScope: VisibilityScope
  /** 予約公開日時（任意）。ISO8601 (LocalDateTime)。 */
  publishAt?: string | null
}

/** BE: {@code UpdateJobPostingRequest}（PATCH セマンティクス。null は現状維持）。 */
export interface UpdateJobPostingRequest {
  title?: string | null
  description?: string | null
  category?: string | null
  workLocationType?: WorkLocationType | null
  workAddress?: string | null
  workStartAt?: string | null
  workEndAt?: string | null
  rewardType?: RewardType | null
  baseRewardJpy?: number | null
  capacity?: number | null
  applicationDeadlineAt?: string | null
  visibilityScope?: VisibilityScope | null
  publishAt?: string | null
}

/** BE: {@code PublishJobPostingRequest} */
export interface PublishJobPostingRequest {
  /** 未指定の場合は即時公開（DRAFT → OPEN）。 */
  publishAt?: string | null
}

/** BE: {@code ApplyRequest} */
export interface ApplyRequest {
  /** 自己PR（500 文字まで）。任意。 */
  selfPr?: string | null
}

/** BE: {@code RejectApplicationRequest} */
export interface RejectApplicationRequest {
  /** 不採用理由（500 文字まで）。任意。 */
  reason?: string | null
}

/** BE: {@code ReportCompletionRequest} */
export interface ReportCompletionRequest {
  /** 完了報告コメント（1000 文字まで）。任意。 */
  message?: string | null
}

/** BE: {@code RejectCompletionRequest} */
export interface RejectCompletionRequest {
  /** 差し戻し理由（1000 文字まで）。必須。 */
  reason: string
}

/** BE: {@code CancelContractRequest} */
export interface CancelContractRequest {
  /** キャンセル理由（500 文字まで）。任意。 */
  reason?: string | null
}

// ==================================================
// Response DTO（BE → FE）
// ==================================================

/** BE: {@code JobPostingResponse}（詳細） */
export interface JobPostingResponse {
  id: number
  teamId: number
  createdByUserId: number
  title: string
  description: string
  category: string | null
  workLocationType: WorkLocationType
  workAddress: string | null
  workStartAt: string
  workEndAt: string
  rewardType: RewardType
  baseRewardJpy: number
  capacity: number
  applicationDeadlineAt: string
  visibilityScope: VisibilityScope
  status: JobPostingStatus
  publishAt: string | null
  createdAt: string
  updatedAt: string
}

/** BE: {@code JobPostingSummaryResponse}（一覧） */
export interface JobPostingSummaryResponse {
  id: number
  teamId: number
  title: string
  category: string | null
  workLocationType: WorkLocationType
  workStartAt: string
  workEndAt: string
  rewardType: RewardType
  baseRewardJpy: number
  capacity: number
  applicationDeadlineAt: string
  visibilityScope: VisibilityScope
  status: JobPostingStatus
  publishAt: string | null
}

/** BE: {@code FeePreviewResponse}（手数料内訳） */
export interface FeePreviewResponse {
  baseRewardJpy: number
  requesterFeeJpy: number
  requesterFeeTaxJpy: number
  requesterTotalJpy: number
  workerFeeJpy: number
  workerReceiptJpy: number
}

/** BE: {@code JobApplicationResponse} */
export interface JobApplicationResponse {
  id: number
  jobPostingId: number
  applicantUserId: number
  selfPr: string | null
  status: JobApplicationStatus
  appliedAt: string
  decidedAt: string | null
  decidedByUserId: number | null
  createdAt: string
  updatedAt: string
}

/** BE: {@code JobContractResponse} */
export interface JobContractResponse {
  id: number
  jobPostingId: number
  jobApplicationId: number
  requesterUserId: number
  workerUserId: number
  chatRoomId: number | null
  baseRewardJpy: number
  workStartAt: string
  workEndAt: string
  status: JobContractStatus
  matchedAt: string
  completionReportedAt: string | null
  completionApprovedAt: string | null
  cancelledAt: string | null
  rejectionCount: number
  lastRejectionReason: string | null
  createdAt: string
  updatedAt: string
}

// ==================================================
// BE PagedResponse.PageMeta（{ total, page, size, totalPages }）
// ==================================================

/**
 * F13.1 API で使用する PagedResponse メタ情報。
 * BE の {@code PagedResponse.PageMeta}（total/page/size/totalPages）と一致。
 *
 * <p>既存の {@code ~/types/api.ts} の {@code PageMeta} は {@code totalElements} を使うが
 * BE 実装と齟齬があるため、F13.1 では BE を真として本型を採用する。</p>
 */
export interface JobPagedMeta {
  total: number
  page: number
  size: number
  totalPages: number
}

/** F13.1 API で使用する {@code PagedResponse<T>}。 */
export interface JobPagedResponse<T> {
  data: T[]
  meta: JobPagedMeta
}

// ==================================================
// QR チェックイン関連（F13.1 Phase 13.1.2）
// ==================================================

/**
 * チェックイン／アウト種別。
 * BE: {@code com.mannschaft.app.jobmatching.enums.JobCheckInType}。
 */
export type JobCheckInType = 'IN' | 'OUT'

/**
 * QR トークン発行／取得レスポンス。
 *
 * <p>BE: {@code com.mannschaft.app.jobmatching.controller.dto.QrTokenResponse}。</p>
 *
 * <p>{@code GET /api/v1/contracts/{id}/qr-tokens/current} で既存トークンを参照するケースでは
 * {@code token}（JWT 文字列）は DB 非保存のため {@code null} になる。
 * その場合クライアントは {@code shortCode} ＋ メタ情報のみで運用するか、{@code issue} で再発行する。</p>
 */
export interface QrTokenResponse {
  /** QR 画像化対象の JWT 文字列。{@code /current} で取得した場合は {@code null}。 */
  token: string | null
  /** 手動入力フォールバック用の短コード（紛らわしい文字を除外した英数 6 桁）。 */
  shortCode: string
  /** IN / OUT 種別。 */
  type: JobCheckInType
  /** 発行時刻（ISO8601 / UTC, ミリ秒精度）。 */
  issuedAt: string
  /** 失効時刻（ISO8601 / UTC, ミリ秒精度）。 */
  expiresAt: string
  /** 発行に用いた署名鍵 ID。 */
  kid: string
}

/** QR トークン発行リクエスト（BE: {@code IssueQrTokenRequest}）。 */
export interface IssueQrTokenRequest {
  /** IN / OUT 種別。 */
  type: JobCheckInType
  /** 任意の TTL（秒）。未指定時は BE の既定値が使用される。 */
  ttlSeconds?: number | null
}

/**
 * QR チェックイン／アウト記録レスポンス。
 *
 * <p>BE: {@code com.mannschaft.app.jobmatching.controller.dto.CheckInResponse}。
 * {@code POST /api/v1/jobs/check-ins} の正常系で返す。足軽陸（Worker 側スキャン）で使用する。</p>
 */
export interface CheckInResponse {
  /** 生成された {@code job_check_ins.id}。 */
  checkInId: number
  /** 対象契約 ID。 */
  contractId: number
  /** IN / OUT 種別。 */
  type: JobCheckInType
  /** 遷移後の契約ステータス（IN は IN_PROGRESS、OUT は CHECKED_OUT）。 */
  newStatus: JobContractStatus
  /** 業務時間（分、OUT 時のみ非 null）。 */
  workDurationMinutes: number | null
  /** Geolocation 乖離フラグ（閾値超なら true）。 */
  geoAnomaly: boolean
}

// ==================================================
// クエリパラメータ
// ==================================================

/** チーム配下求人検索のクエリパラメータ。 */
export interface SearchJobsParams {
  teamId: number
  status?: JobPostingStatus | null
  page?: number
  size?: number
}

/** ページング共通パラメータ。 */
export interface JobPageParams {
  page?: number
  size?: number
}
