export interface AdminBusinessAlertAlerts {
  newReservations: number
  pendingApproval: number
  unreadInquiries: number
}

export interface AdminBusinessAlertLinks {
  reservationsUrl: string
  inquiryChannelUrl: string | null
}

export interface AdminBusinessAlertTeam {
  teamId: number
  teamName: string
  reservationModuleEnabled: boolean
  alerts: AdminBusinessAlertAlerts
  links: AdminBusinessAlertLinks
}

export interface AdminBusinessAlertData {
  teams: AdminBusinessAlertTeam[]
  totalPending: number
}

/**
 * BE {@code AdminBusinessAlertSummaryResponse.java} と一致する内側 DTO。
 * この DTO 自体がフィールド名 `data` を持つ（BE 設計上の固有事情）。
 */
export interface AdminBusinessAlertSummaryInner {
  data: AdminBusinessAlertData
}

/**
 * `GET /api/v1/admin/business-alerts/summary` の実応答全体（`ApiResponse<AdminBusinessAlertSummaryResponse>`）。
 *
 * 注意（二重ネスト）: 外側の `ApiResponse` ラッパーに加え、内側の
 * {@link AdminBusinessAlertSummaryInner} 自体も `data` フィールドを持つため、
 * 実際のレスポンスは `{ data: { data: { teams, totalPending } } }` という二重ネストになる
 * （他の BE DTO には無い、この API 固有の形状。実機E2E business-alert.spec.ts BA-003 で検証済み）。
 */
export interface AdminBusinessAlertSummaryResponse {
  data: AdminBusinessAlertSummaryInner
}
