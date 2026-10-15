export interface CareLinkResponse {
  id: number
  careRecipientUserId: number
  careRecipientDisplayName: string
  watcherUserId: number
  watcherDisplayName: string
  careCategory: CareCategory
  relationship: CareRelationship
  isPrimary: boolean
  status: CareLinkStatus
  invitedBy: CareLinkInvitedBy
  confirmedAt: string | null
  notifyOnRsvp: boolean
  notifyOnCheckin: boolean
  notifyOnCheckout: boolean
  notifyOnAbsentAlert: boolean
  notifyOnDismissal: boolean
  createdAt: string
}

export interface InviteWatcherRequest {
  watcherUserId: number
  careCategory: CareCategory
  relationship: CareRelationship
  isPrimary?: boolean
}

export interface InviteRecipientRequest {
  careRecipientUserId: number
  careCategory: CareCategory
  relationship: CareRelationship
}

export interface CareLinkNotifySettingsRequest {
  notifyOnRsvp?: boolean
  notifyOnCheckin?: boolean
  notifyOnCheckout?: boolean
  notifyOnAbsentAlert?: boolean
  notifyOnDismissal?: boolean
}

export interface CareLinkInvitationResponse {
  token: string
  inviterDisplayName: string
  careCategory: CareCategory
  relationship: CareRelationship
  expiresAt: string
}

export type CareCategory = 'MINOR' | 'ELDERLY' | 'DISABILITY_SUPPORT' | 'GENERAL_FAMILY'
export type CareRelationship =
  | 'PARENT'
  | 'CHILD'
  | 'SPOUSE'
  | 'GRANDPARENT'
  | 'GRANDCHILD'
  | 'SIBLING'
  | 'LEGAL_GUARDIAN'
  | 'CARETAKER'
  | 'OTHER'
export type CareLinkStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'REVOKED'
export type CareLinkInvitedBy = 'CARE_RECIPIENT' | 'WATCHER' | 'ADMIN' | 'SYSTEM'
