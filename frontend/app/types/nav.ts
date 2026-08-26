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

/**
 * サイドバー化 Phase1: グローバルサイドバーのグループ見出しキー。
 * `frontend/app/constants/navGroups.ts` の対応表で item.key → groupKey を解決する。
 */
export type NavGroupKey = 'home' | 'connections' | 'living' | 'work' | 'account' | 'admin' | 'other'

/**
 * グローバルサイドバーの単一項目。
 * useNavSettingsStore の NavFeatureItem、および固定/条件付き項目（ダッシュボード・
 * 代理入力デスク・SYSTEM・同期）の両方をこの共通形へ射影して扱う。
 */
export interface GlobalNavItem {
  key: string
  labelKey: string
  icon: string
  path: string
  /** バッジ件数。実データに基づく項目（同期コンフリクト数等）のみ設定する */
  badgeCount?: number
  /** 'admin' は管理系項目であることを示す（強調表示に使用） */
  variant?: 'default' | 'admin'
}

/** グループ見出し＋配下項目（useAppNavGroups の射影結果） */
export interface SidebarGroup {
  key: NavGroupKey
  labelKey: string
  items: GlobalNavItem[]
}
