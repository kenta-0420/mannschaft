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
  /**
   * 機能ゲート（gate_key。`constants/featureGates.ts` の GATE_ROUTE_MAP と同じ値）。
   * 指定された項目は、そのフラグが ON のときだけ表示する（OFF・未取得は非表示。SYSTEM_ADMIN も同様）。
   * route 層 middleware が弾くページへの導線を出さない（見えるものは必ず踏める）ための仕組み。
   */
  gateKey?: string
  requiredRole: 'MEMBER' | 'DEPUTY_ADMIN' | 'ADMIN'
  access?: 'loyalty-operate' | 'scope-admin'
}

export interface SidebarCategory {
  key: string
  labelKey: string
  icon: string
  items: SidebarItem[]
}
