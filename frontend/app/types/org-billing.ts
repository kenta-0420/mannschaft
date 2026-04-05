/** 組織種別 */
export type OrgBillingType = 'NONPROFIT' | 'FORPROFIT'

/** 組織種別ごとの課金設定レスポンス */
export interface OrgBillingSettingsResponse {
  orgType: OrgBillingType
  freeTeams: number
  overageUnitPrice: number
  currency: string
  updatedAt: string
}

/** 組織種別ごとの課金設定更新リクエスト */
export interface UpdateOrgBillingSettingsRequest {
  freeTeams: number
  overageUnitPrice: number
}

/** 組織課金ステータス */
export type OrgBillingStatus = 'FREE' | 'BILLABLE' | 'OVERDUE'

/** 組織一覧（課金状況付き）レスポンス */
export interface OrgBillingOrganizationResponse {
  id: number
  name: string
  orgType: OrgBillingType
  teamCount: number
  freeTeams: number
  overageTeams: number
  monthlyCharge: number
  billingStatus: OrgBillingStatus
  updatedAt: string
}
