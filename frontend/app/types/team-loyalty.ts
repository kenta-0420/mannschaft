import type { components } from '~/types/generated'

// API正本のrequired/null/enumを再定義せず、正式生成型へ接続する。
export type LoyaltySettings = components['schemas']['LoyaltyProgramSettings']
export type LoyaltyAudience = LoyaltySettings['targetAudience']
export type SelfAwardMode = LoyaltySettings['selfAwardMode']
export type LoyaltyRule = components['schemas']['LoyaltyRuleResponse']
export type LoyaltyReward = components['schemas']['LoyaltyRewardResponse']
export type RewardProgress = components['schemas']['LoyaltyRewardProgress']
export type LoyaltyAccountSummary = components['schemas']['LoyaltyAccountSummary']
export type LoyaltyAccount = components['schemas']['LoyaltyAccountDetail']
export type LoyaltyTransaction = components['schemas']['LoyaltyTransactionResponse']
export type LoyaltyCatalog = components['schemas']['LoyaltyOperationCatalog']
export type LoyaltyScanPreview = components['schemas']['LoyaltyScanPreview']
export type LoyaltyRedemptionPreview = components['schemas']['LoyaltyRedemptionPreview']
export type LoyaltySelfPreview = components['schemas']['LoyaltySelfAwardPreview']
export type LoyaltyBootstrap = components['schemas']['LoyaltySelfAwardBootstrap']
export type LoyaltyAwardRequest = components['schemas']['LoyaltyAwardRequestResponse']
export type AwardRequestStatus = LoyaltyAwardRequest['status']
export type LoyaltySelfResult = components['schemas']['LoyaltySelfAwardResult']
export type LoyaltyStaffAwardBody = components['schemas']['LoyaltyStaffAwardRequest']
export type LoyaltyRedeemBody = components['schemas']['LoyaltyRedeemRequest']
export type LoyaltySelfAwardBody = components['schemas']['LoyaltySelfAwardRequest']
export type LoyaltyRequestAction = components['schemas']['LoyaltyRequestActionRequest']
export type LoyaltyApproveBody = components['schemas']['LoyaltyApproveRequest']
export type CreateLoyaltyRule = components['schemas']['LoyaltyCreateRuleRequest']
export type CreateLoyaltyReward = components['schemas']['LoyaltyCreateRewardRequest']

// 共通CursorPagedResponseの生成契約は変更せず、本機能の必須data/metaを保つ。
export interface LoyaltyPage<T> {
  data: T[]
  meta: { nextCursor: string | null; hasNext: boolean; limit: number }
}
