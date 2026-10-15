/**
 * 柱③-A「MEMBER 参加申請（join request）」FE composable（CMP-260901-1538）。
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/joinrequest/controller/JoinRequestController.java
 *   - POST /api/v1/{teams|organizations}/{scopeId}/join-requests            申請作成
 *   - GET  /api/v1/{teams|organizations}/{scopeId}/join-requests/me        自分の申請一覧
 *   - GET  /api/v1/{teams|organizations}/{scopeId}/join-requests           審査一覧（ADMIN/DEPUTY_ADMIN）
 *   - POST /api/v1/{teams|organizations}/{scopeId}/join-requests/{id}/approve|reject
 *
 * # スコープ ID について
 *  BE の `{teamId}` / `{organizationId}` パス変数は `Long` 専用（slug 非対応）。
 *  呼び出し側は `TeamResponse.numericId` / `OrganizationResponse.numericId` を渡すこと
 *  （`id`/`slug` は URL 識別子であり BE には渡せない）。
 *
 * # 生成型を使わない理由（既知の制約）
 *  `docs/openapi.json` は村（village）の参加申請と本機能（team/organization）の参加申請とで
 *  スキーマ名 `JoinRequestResponse` / `JoinRequestCreateRequest` が衝突しており、
 *  springdoc の出力上は後勝ちで村側の形状（villageId/subjectId/subjectType 等）に
 *  上書きされてしまっている（`frontend/app/types/generated/index.ts` を参照）。
 *  そのため生成型をそのまま使うと実体と異なるフィールド形状になる。
 *  根治（BE 側のスキーマ名分離）は別チケットの対象とし、本 composable では
 *  実際の BE DTO（`JoinRequestResponse`/`JoinRequestCreateRequest`/`JoinRequestReviewRequest`）に
 *  忠実なローカル型を手書きする（`useTeamSupporters.ts` 等、既存コードベースの慣行と同型）。
 *
 * # 取り下げ（withdraw）が無いことについて
 *  村の参加申請には `POST .../join-requests/{id}/withdraw` があるが、
 *  team/organization の参加申請（PR #3139）には取り下げ API が実装されていない。
 *  BE 未実装の機能を FE だけで偽装しない（対処療法禁止の原則）ため、
 *  本 composable・関連 UI は取り下げを提供しない。将来 BE に追加された時点で FE も追随する。
 */

export type JoinRequestScopeType = 'team' | 'organization'
export type JoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/**
 * 「自分の参加申請状態」の UI 表示用ステート（Codex 検分 CMP-260901-1538 P1-1 是正）。
 *
 * `NONE` は「BE から未申請と確認できた」ことを意味し、これのみ申請ボタンを有効化する。
 * `UNKNOWN`（初期値・未取得）・`LOADING`（取得中）・`ERROR`（取得失敗）はすべて
 * fail-close（申請操作を無効化）する。取得失敗を握りつぶして `NONE` に潰すと
 * 「未申請」と誤表示したまま再送信可能になる fail-open になるため、必ず区別する。
 */
export type JoinRequestUiStatus = 'UNKNOWN' | 'LOADING' | 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'ERROR'

export interface JoinRequestResponse {
  id: string
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  requesterUserId: number
  message: string | null
  status: JoinRequestStatus
  reviewerUserId: number | null
  reviewedAt: string | null
  reviewComment: string | null
  createdAt: string
}

export interface JoinRequestPageResponse {
  content: JoinRequestResponse[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

function scopeBase(scopeType: JoinRequestScopeType, scopeId: number): string {
  const segment = scopeType === 'team' ? 'teams' : 'organizations'
  return `/api/v1/${segment}/${scopeId}/join-requests`
}

export function useJoinRequestApi() {
  const api = useApi()

  /** 参加申請を作成する（PENDING 中の再申請は BE 側で同一申請を返す＝冪等）。 */
  async function createJoinRequest(
    scopeType: JoinRequestScopeType,
    scopeId: number,
    message?: string,
  ) {
    return api<{ data: JoinRequestResponse }>(scopeBase(scopeType, scopeId), {
      method: 'POST',
      body: message ? { message } : undefined,
    })
  }

  /** 自分の参加申請一覧（申請者本人。createdAt 降順の素の配列）。 */
  async function listMyJoinRequests(scopeType: JoinRequestScopeType, scopeId: number) {
    return api<{ data: JoinRequestResponse[] }>(`${scopeBase(scopeType, scopeId)}/me`)
  }

  /** 審査一覧（ADMIN/DEPUTY_ADMIN 向け。Page で返る）。 */
  async function listJoinRequestsForReview(
    scopeType: JoinRequestScopeType,
    scopeId: number,
    params?: { status?: JoinRequestStatus, page?: number, size?: number },
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: JoinRequestPageResponse }>(
      `${scopeBase(scopeType, scopeId)}?${query}`,
    )
  }

  /** 承認（ADMIN/DEPUTY_ADMIN のみ）。 */
  async function approveJoinRequest(
    scopeType: JoinRequestScopeType,
    scopeId: number,
    id: string,
    reviewComment?: string,
  ) {
    return api<{ data: JoinRequestResponse }>(
      `${scopeBase(scopeType, scopeId)}/${id}/approve`,
      { method: 'POST', body: reviewComment ? { reviewComment } : undefined },
    )
  }

  /** 却下（ADMIN/DEPUTY_ADMIN のみ）。 */
  async function rejectJoinRequest(
    scopeType: JoinRequestScopeType,
    scopeId: number,
    id: string,
    reviewComment?: string,
  ) {
    return api<{ data: JoinRequestResponse }>(
      `${scopeBase(scopeType, scopeId)}/${id}/reject`,
      { method: 'POST', body: reviewComment ? { reviewComment } : undefined },
    )
  }

  return {
    createJoinRequest,
    listMyJoinRequests,
    listJoinRequestsForReview,
    approveJoinRequest,
    rejectJoinRequest,
  }
}
