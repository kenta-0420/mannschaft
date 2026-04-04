export interface ContactUser {
  id: number
  displayName: string
  contactHandle: string | null
  avatarUrl: string | null
}

export interface ContactResponse {
  folderItemId: number
  folderId: number | null
  user: ContactUser
  customName: string | null
  isPinned: boolean
  privateNote: string | null
  addedAt: string
}

export interface ContactRequestResponse {
  id: number
  requester: ContactUser
  target: ContactUser
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
  message: string | null
  sourceType: string
  createdAt: string
}

export interface ContactRequestBlockResponse {
  id: number
  blockedUser: ContactUser
  createdAt: string
}

export interface ContactInviteTokenResponse {
  id: number
  token: string
  label: string | null
  inviteUrl: string
  qrCodeUrl: string
  maxUses: number | null
  usedCount: number
  expiresAt: string | null
  createdAt: string
}

export interface ContactInvitePreviewResponse {
  isValid: boolean
  issuer: { displayName: string; contactHandle: string | null }
  expiresAt: string | null
}

export interface ContactPrivacySettings {
  handleSearchable: boolean
  contactApprovalRequired: boolean
  dmReceiveFrom: 'ANYONE' | 'TEAM_MEMBERS_ONLY' | 'CONTACTS_ONLY'
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}

export interface HandleSearchResult {
  userId: number
  displayName: string
  contactHandle: string
  avatarUrl: string | null
  isContact: boolean
  hasPendingRequest: boolean
  contactApprovalRequired: boolean
}

export interface HandleInfo {
  contactHandle: string | null
  handleSearchable: boolean
  contactApprovalRequired: boolean
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}

export interface ContactableMember {
  userId: number
  displayName: string
  contactHandle: string | null
  avatarUrl: string | null
  isContact: boolean
  hasPendingRequest: boolean
}

export interface SendContactRequestBody {
  targetUserId: number
  message?: string
  sourceType: 'HANDLE_SEARCH' | 'TEAM_SEARCH' | 'ORG_SEARCH' | 'INVITE_URL'
}

export interface CreateInviteTokenBody {
  label?: string
  maxUses?: number | null
  expiresIn?: '1d' | '7d' | '30d' | null
}
