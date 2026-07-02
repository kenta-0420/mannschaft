/**
 * スコープ詳細「永続シェル」（ScopePageShell）で使うタブ定義型。
 *
 * # 背景
 *  チーム/組織ダッシュボードのウィジェット押下時、従来はフルページ遷移で
 *  ヘッダ・タブ・文脈が消えていた。これを「永続シェル（ヘッダ＋タブ＋
 *  サイドバー Drawer＋<NuxtPage/>）」で中身だけ差し替える SPA 構造に改める。
 *  その共通骨格 `components/scope/ScopePageShell.vue` が受け取るタブ配列の要素型。
 *
 *  村（VillageHeader）のルート連動タブと同じ思想（末尾セグメント → activeTab）で、
 *  各タブは自分の遷移先ルート `to` と表示ラベルの i18n キー `labelKey` を持つ。
 */
export interface ScopeTab {
  /** タブ識別キー（末尾セグメント → activeTab 導出に使う。例: 'dashboard' / 'info'）。 */
  key: string
  /** 遷移先ルート（<NuxtLink :to>）。絶対パスで指定する（例: '/teams/foo/info'）。 */
  to: string
  /** PrimeIcons クラス（例: 'pi pi-home'）。 */
  icon: string
  /** 表示ラベルの i18n キー（直書き禁止・必ず $t で解決する）。 */
  labelKey: string
  /**
   * 表示可否。省略時（undefined）は表示扱い。
   * `false` のときのみタブを描画しない（権限出し分けに使う）。
   */
  visible?: boolean
}
