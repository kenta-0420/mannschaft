export interface FeatureDisplayPolicy {
  featureKey: string
  memberVisible: boolean
  supporterVisible: boolean
}
export type FeatureDisplayRole =
  | 'SYSTEM_ADMIN'
  | 'ADMIN'
  | 'DEPUTY_ADMIN'
  | 'MEMBER'
  | 'SUPPORTER'
  | 'PUBLIC'
  | 'GUEST'
  | null
  | undefined
