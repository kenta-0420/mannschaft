export type SealStatus = 'SEALED' | 'UNSEAL_REQUESTED' | 'UNSEALED' | 'RE_SEALED'
export type UnsealRequestStatus = 'PENDING' | 'FIRST_APPROVED' | 'UNSEALED' | 'RE_SEALED' | 'CANCELLED'

export interface UnsealRequestResponse {
  id: string
  organizationId: number
  preRegistrationId: string
  requestedBy: number
  requestReason: string
  status: UnsealRequestStatus
  firstApproverUserId: number | null
  secondApproverUserId: number | null
  autoResealAt: string | null
  reSealedAt: string | null
  cancelledAt: string | null
  createdAt: string
  updatedAt: string
}

export interface UnsealRequestCreateRequest {
  preRegistrationId: string
  reason: string
}

export interface UnsealApprovalRequest {
  comment?: string | null
}
