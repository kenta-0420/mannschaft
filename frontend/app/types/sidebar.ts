export interface SidebarItem {
  labelKey: string
  icon: string
  path: string
  moduleSlug: string | null
  requiredRole: 'MEMBER' | 'DEPUTY_ADMIN' | 'ADMIN'
}

export interface SidebarCategory {
  key: string
  labelKey: string
  icon: string
  items: SidebarItem[]
}
