export interface CommitteeSummary {
  id: number
  organizationId: number
  name: string
  description: string | null
  purposeTag: string | null
  status: 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'ARCHIVED' | 'CANCELLED_DRAFT'
  visibilityToOrg: 'HIDDEN' | 'NAME_ONLY' | 'NAME_AND_PURPOSE'
  memberCount: number
  startDate: string | null
  endDate: string | null
  createdAt: string
}

export interface CommitteeDetail extends CommitteeSummary {
  defaultConfirmationMode: 'NONE' | 'OPTIONAL' | 'REQUIRED'
  defaultAnnouncementEnabled: boolean
  defaultDistributionScope: 'COMMITTEE_ONLY' | 'PARENT_ORG' | 'PARENT_ORG_AND_CHILDREN'
  archivedAt: string | null
  myRole: CommitteeRole | null
}

export type CommitteeRole = 'CHAIR' | 'VICE_CHAIR' | 'SECRETARY' | 'MEMBER'

export interface CommitteeMember {
  userId: number
  displayName: string
  avatarUrl: string | null
  role: CommitteeRole
  joinedAt: string
  leftAt: string | null
}

export interface CommitteeInvitation {
  id: number
  committeeId: number
  inviteeUserId: number
  inviteeDisplayName: string
  proposedRole: CommitteeRole
  expiresAt: string
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED'
}

export interface CommitteeDistributionLog {
  id: number
  committeeId: number
  contentType: string
  contentId: number | null
  targetScope: string
  announcementEnabled: boolean
  confirmationMode: 'NONE' | 'OPTIONAL' | 'REQUIRED'
  confirmableNotificationId: number | null
  announcementFeedIds: number[]
  customTitle: string | null
  customHeadline: string | null
  createdAt: string
}
