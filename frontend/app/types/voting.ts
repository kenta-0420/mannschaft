export type VoteSessionStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'FINALIZED'
export type VotingMode = 'MEETING' | 'WRITTEN'
export type MotionVotingStatus = 'PENDING' | 'VOTING' | 'VOTED'
export type ApprovalRule = 'MAJORITY' | 'TWO_THIRDS' | 'UNANIMOUS'
export type VoteChoice = 'FOR' | 'AGAINST' | 'ABSTAIN'
export type DelegationStatus = 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'

export interface VoteSessionResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  description: string | null
  votingMode: VotingMode
  isAnonymous: boolean
  status: VoteSessionStatus
  votingStartAt: string | null
  votingEndAt: string | null
  eligibleCount: number
  votedCount: number
  delegatedCount: number
  createdBy: { id: number; displayName: string }
  motions: VoteMotionResponse[]
  createdAt: string
  updatedAt: string
}

export interface VoteMotionResponse {
  id: number
  sessionId: number
  title: string
  description: string | null
  approvalRule: ApprovalRule
  votingStatus: MotionVotingStatus
  sortOrder: number
  result: VoteMotionResult | null
}

export interface VoteMotionResult {
  forCount: number
  againstCount: number
  abstainCount: number
  totalVotes: number
  isApproved: boolean
  quorumMet: boolean
}

export interface VoteDelegation {
  id: number
  sessionId: number
  delegatorId: number
  delegatorName: string
  delegateId: number | null
  delegateName: string | null
  status: DelegationStatus
  createdAt: string
}

export interface VoteAttachment {
  id: number
  targetType: 'SESSION' | 'MOTION'
  targetId: number
  fileName: string
  fileSize: number
  attachmentType: 'MINUTES' | 'DOCUMENT' | 'OTHER'
  url: string
  createdAt: string
}
