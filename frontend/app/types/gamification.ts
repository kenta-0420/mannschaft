export type BadgeConditionType = 'PERFECT_ATTENDANCE' | 'MVP' | 'POST_MASTER' | 'STREAK' | 'CUSTOM'

export interface GamificationConfig {
  enabled: boolean
  pointsEnabled: boolean
  badgesEnabled: boolean
  rankingEnabled: boolean
}

export interface PointRule {
  id: number
  actionType: string
  points: number
  description: string
  isActive: boolean
}

export interface Badge {
  id: number
  name: string
  description: string
  iconUrl: string | null
  conditionType: BadgeConditionType
  conditionValue: number | null
  isSystemBadge: boolean
  createdAt: string
}

export interface UserBadge {
  id: number
  badge: Badge
  awardedAt: string
  awardedBy: string | null
}

export interface PointSummary {
  totalPoints: number
  monthlyPoints: number
  badgeCount: number
}

export interface PointHistory {
  id: number
  actionType: string
  points: number
  description: string
  createdAt: string
}

export interface RankingEntry {
  rank: number
  userId: number
  displayName: string
  avatarUrl: string | null
  points: number
}

export interface GamificationPrivacy {
  showInRanking: boolean
  showBadges: boolean
}
