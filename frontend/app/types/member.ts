export type RoleName = 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'GUEST'

export interface MemberResponse {
  userId: number
  displayName: string
  avatarUrl: string | null
  roleName: RoleName
  joinedAt: string
}

export interface RoleChangeRequest {
  roleId: number
}

export interface EffectivePermissionsResponse {
  roleName: RoleName
  permissions: string[]
}

// 招待トークン
export interface InviteTokenResponse {
  id: number
  token: string
  roleName: RoleName
  expiresAt: string | null
  maxUses: number | null
  usedCount: number
  revokedAt: string | null
  createdAt: string
}

export interface CreateInviteTokenRequest {
  roleId: number
  expiresIn: '1d' | '7d' | '30d' | '90d' | null
  maxUses: number | null
}

export interface InvitePreviewResponse {
  id: number
  name: string
  type: 'ORGANIZATION' | 'TEAM'
  description: string | null
  iconUrl: string | null
  roleName: RoleName
  expiresAt: string | null
  isValid: boolean
}

// 権限グループ
export interface PermissionResponse {
  id: number
  code: string
  displayName: string
  description: string | null
}

export interface PermissionGroupResponse {
  id: number
  name: string
  description: string | null
  targetRole: 'DEPUTY_ADMIN' | 'MEMBER'
  permissions: string[]
  createdAt: string
}

export interface CreatePermissionGroupRequest {
  name: string
  description?: string
  targetRole: 'DEPUTY_ADMIN' | 'MEMBER'
  permissionIds: number[]
}

export interface UpdatePermissionGroupRequest {
  name?: string
  description?: string
  permissionIds?: number[]
}

export interface AssignPermissionGroupsRequest {
  groupIds: number[]
}

// ブロック
export interface BlockResponse {
  id: number
  userId: number
  displayName: string
  reason: string | null
  createdAt: string
}

export interface CreateBlockRequest {
  userId: number
  reason?: string
}

// スコープコンテキスト
export type ScopeType = 'team' | 'organization'

export interface ScopeContext {
  type: ScopeType
  id: number
  name: string
  iconUrl: string | null
  role: RoleName
}

export interface MyTeamResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: RoleName
  template: string
}

export interface MyOrganizationResponse {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: RoleName
  orgType: string
}
