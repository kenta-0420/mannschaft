export interface SidebarItem {
  labelKey: string
  icon: string
  /**
   * スコープ基点からの相対パス（`/organizations/{id}/` または `/teams/{id}/` に連結される）。
   * 空文字はタブ遷移アイテム（`tabNavigate` を emit する）を表す。
   * `absolutePath` を持つ項目ではこの値は使われない（慣例として空文字を入れる）。
   */
  path: string
  /**
   * スコープ配下に存在しない画面（例: `/admin/receipts` のようなスコープ横断ルート）への
   * 絶対パス。指定された場合は `path` より優先し、そのままリンク先に使う。
   */
  absolutePath?: string
  moduleSlug: string | null
  requiredRole: 'MEMBER' | 'DEPUTY_ADMIN' | 'ADMIN'
}

export interface SidebarCategory {
  key: string
  labelKey: string
  icon: string
  items: SidebarItem[]
}
