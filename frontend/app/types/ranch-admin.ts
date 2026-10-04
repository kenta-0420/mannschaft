// 公開管理DTOの暫定手動型。最終統合OpenAPIからの正規生成で収束する。
export type RanchRewardSourceType = 'ATTENDANCE_RESPONSE' | 'TIMELINE_ORIGINAL' | 'BLOG_FIRST_PUBLISH' | 'PERSONAL_RECALL_COMPLETE'
export interface RanchOperationalControls {
 version: string; isCareEnabled: boolean; isShopEnabled: boolean;
 isDeliveryPaused: boolean; isRewardsPaused: boolean; updatedAt: string
}
export type RanchOperationalControlsRequest = Omit<RanchOperationalControls, 'updatedAt'> & { reasonCode: string }
export interface RanchCareRulePublicationRequest {
 effectiveAt: string; amountXp: string; weeklyCapXp: string;
 juvenileXp: string; adultXp: string; reasonCode: string
}
export interface RanchPolicyPublicationRequest {
 effectiveAt: string; enabled: boolean; globalWeeklyCap: string;
 sources: { sourceType: RanchRewardSourceType; enabled: boolean; amountPoints: string; countLimit: number }[];
 delivery: { batchSize: number; leaseSeconds: number; maxAttempts: number; initialBackoffSeconds: number; maxBackoffSeconds: number };
 reasonCode: string
}
export interface RanchPublicationAck<T> {
 id: string; version: string; contentHash: string; effectiveAt: string;
 settings: T; publishedAt: string; publishedBy: string
}
export interface RanchSourceHealth {
 sources: { sourceType: RanchRewardSourceType; pendingCount: string; deadCount: string; oldestAgeSeconds: number | null }[];
 observedAt: string
}
export interface RanchSourceRetryAck {
 commandId: string; sourceType: RanchRewardSourceType; eventId: string;
 disposition: 'RETRY_SCHEDULED' | 'ALREADY_TERMINAL'; completedAt: string
}
