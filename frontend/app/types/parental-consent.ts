export type ParentalConsentStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'

export interface InvitationResponse {
  linkId: string
  parentEmail: string
  status: ParentalConsentStatus
  expiresAt: string
  createdAt: string
}

export interface ParentLinkResponse {
  linkId: string
  parentEmail: string
  parentUserId: number | null
  approvedAt: string
}

export interface ChildLinkResponse {
  linkId: string
  childUserId: number
  childDisplayName: string | null
  approvedAt: string
}
