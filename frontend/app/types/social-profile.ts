export type FollowTargetType = 'USER' | 'SOCIAL_PROFILE'

export interface SocialProfile {
  id: number
  handle: string
  displayName: string
  avatarUrl: string | null
  bio: string | null
  isActive: boolean
  followerCount: number
  followingCount: number
  isFollowing?: boolean
  createdAt: string
}

export interface CreateSocialProfileRequest {
  handle: string
  displayName: string
  bio?: string
}

export interface FollowRecord {
  id: number
  followedType: FollowTargetType
  followedId: number
  displayName: string
  handle?: string
  avatarUrl: string | null
  createdAt: string
}

export interface FollowCheckResponse {
  isFollowing: boolean
}
