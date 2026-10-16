export type LoyaltyAudience = 'ALL_AUTHENTICATED' | 'SUPPORTERS_AND_ABOVE' | 'MEMBERS_AND_ABOVE'
export type SelfAwardMode = 'STAFF_APPROVAL' | 'AUTO'
export interface LoyaltyRule {
  id: string; name: string; points: number; frequency: 'ONCE_PER_JST_DAY' | 'EACH_STAFF_CONFIRMATION'
  isSelfAwardEnabled: boolean; isActive: boolean; version: number
}
export interface LoyaltyReward {
  id: string; name: string; description: string | null; requiredPoints: number; isActive: boolean; version: number
}
export interface RewardProgress extends LoyaltyReward { remainingPoints: number; canRedeem: boolean }
export interface LoyaltyAccountSummary {
  accountId: string; teamId: number; teamName: string; balance: number; lifetimeEarned: number; updatedAt: string
}
export interface LoyaltyAccount {
  accountId: string | null; teamId: number; teamName: string; balance: number; lifetimeEarned: number
  isProgramEnabled: boolean; isEligibleForAward: boolean; rewards: RewardProgress[]
}
export interface LoyaltyTransaction {
  id: string; teamId: number; kind: 'AWARD' | 'REDEEM'; channel: 'STAFF' | 'SELF_AUTO' | 'SELF_APPROVED'
  delta: number; balanceAfter: number; ruleId: string | null; rewardId: string | null; label: string
  awardOn: string | null; createdAt: string
}
export interface LoyaltyPage<T> { data: T[]; meta: { nextCursor: string | null; hasNext: boolean; limit: number } }
export interface LoyaltySettings {
  id: string | null; teamId: number; isEnabled: boolean; targetAudience: LoyaltyAudience
  selfAwardMode: SelfAwardMode; version: number | null; rules: LoyaltyRule[]; rewards: LoyaltyReward[]
}
export interface LoyaltyCatalog {
  teamId: number; teamName: string; isEnabled: boolean; programVersion: number | null
  selfAwardMode: SelfAwardMode; rule: LoyaltyRule | null; rewards: LoyaltyReward[]
}
export interface LoyaltyScanPreview {
  displayName: string; balance: number; rule: LoyaltyRule; programVersion: number; isEligibleForAward: boolean
}
export interface LoyaltyRedemptionPreview {
  displayName: string; balance: number; reward: LoyaltyReward; programVersion: number; canRedeem: boolean
}
export interface LoyaltySelfPreview {
  teamId: number; teamName: string; rule: LoyaltyRule; programVersion: number; selfAwardMode: SelfAwardMode
  balance: number; isEligibleForAward: boolean; isAlreadyAwardedToday: boolean
}
export interface LoyaltyBootstrap { preview: LoyaltySelfPreview; account: LoyaltyAccount }
export type AwardRequestStatus = 'PENDING' | 'APPROVED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED' | 'INVALIDATED' | 'SUPERSEDED'
export interface LoyaltyAwardRequest {
  id: string; teamId: number; teamName: string; applicantDisplayName: string; locationName: string
  ruleName: string; points: number; awardOn: string; verificationCode: string; status: AwardRequestStatus
  reason: string | null; version: number; createdAt: string; expiresAt: string; closedAt: string | null
  transactionId: string | null; canApprove: boolean; canCancel: boolean
}
export interface LoyaltySelfResult {
  outcome: 'AWARDED' | 'SUBMITTED'; transaction: LoyaltyTransaction | null; request: LoyaltyAwardRequest | null
}
export interface LoyaltyStaffAwardBody { qrToken: string; ruleId: string; expectedRuleVersion: number; expectedProgramVersion: number }
export interface LoyaltyRedeemBody { qrToken: string; rewardId: string; expectedRewardVersion: number; expectedProgramVersion: number }
export interface LoyaltySelfAwardBody { locationToken: string; ruleId: string; expectedRuleVersion: number; expectedProgramVersion: number }
export interface LoyaltyRequestAction { expectedRequestVersion: number }
export interface LoyaltyApproveBody extends LoyaltyRequestAction { verificationCode: string }
export type CreateLoyaltyRule = Omit<LoyaltyRule, 'id' | 'version' | 'isActive'>
export type CreateLoyaltyReward = Omit<LoyaltyReward, 'id' | 'version' | 'isActive'>
