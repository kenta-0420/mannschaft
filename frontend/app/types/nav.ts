export interface NavFeatureItem {
  key: string
  labelKey: string
  icon: string
  path: string
  fixed: boolean
  sortOrder: number
  mobileVisible: boolean
  visible: boolean
}

export interface NavSettingsResponse {
  features: NavFeatureItem[]
}

export interface UpdateNavSettingsRequest {
  hiddenNavKeys: string[]
  /** 個人別ナビ表示順（nav_features.key の配列）。省略時はマスタ順にリセット。 */
  navDisplayOrder?: string[]
}

export interface NavFeatureAdminItem {
  key: string
  labelKey: string
  icon: string
  path: string
  fixed: boolean
  enabled: boolean
  subscriptionRequired: boolean
  sortOrder: number
  mobileVisible: boolean
  createdAt: string
  updatedAt: string
}

export interface NavFeatureCreateRequest {
  key: string
  labelKey: string
  icon: string
  path: string
  fixed: boolean
  enabled: boolean
  subscriptionRequired: boolean
  sortOrder: number
  mobileVisible: boolean
}

export interface NavFeatureUpdateRequest {
  labelKey: string
  icon: string
  path: string
  fixed: boolean
  enabled: boolean
  subscriptionRequired: boolean
  sortOrder: number
  mobileVisible: boolean
}
