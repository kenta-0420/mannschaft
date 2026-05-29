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
