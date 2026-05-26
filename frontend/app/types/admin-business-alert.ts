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

export interface AdminBusinessAlertSummaryResponse {
  data: AdminBusinessAlertData
}
