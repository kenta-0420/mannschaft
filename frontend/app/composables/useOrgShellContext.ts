import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { ViewerRole, WidgetVisibilitySetting } from '~/types/dashboard'
import type { OrgDetail } from '~/composables/useOrgDetail'
import type {
  AncestorOrganization,
  ChildOrganization,
  OrgTeam,
  OrgPermissionGroup,
} from '~/types/organization'

/**
 * 組織詳細「永続シェル」用 provide/inject コンテキスト。
 *
 * # 背景（チーム／村方式の踏襲）
 *  従来組織詳細は `pages/organizations/[slug]/index.vue` がヘッダ・タブ・全パネルを
 *  抱え込み、ウィジェット押下でフルページ遷移して文脈が消えていた。
 *  これをチーム（`useTeamShellContext` / `pages/teams/[slug].vue`）と同型の
 *  「永続シェル方式（SPA）」に改める。
 *
 *  親レイアウトルート `pages/organizations/[slug].vue` で組織・権限・階層・チーム一覧を
 *  1 度だけ解決し、`<NuxtPage>` の子タブへ provide する。子タブ（index / info / members …）
 *  は本コンテキストを inject し、権限で二重ガードしつつ各パネルへ結線する。
 *
 * # 設計上の配慮（useTeamShellContext を範とする）
 *  - `inject(key)!` の非 null アサーション散在を避けるため、未 provide 時に明示的に
 *    throw する `useOrgShellContext()` アクセサを公開する。
 *  - OrgPageHeader / OrgInfoTab の icon/banner 更新を親へ通知するため、親 org ref を
 *    子から更新できるミューテータも含める。
 */

/** 親が保持する組織 ref を子から部分更新するためのミューテータ群。 */
export interface OrgShellMutators {
  /** アイコン URL を更新（OrgPageHeader の icon-updated 由来）。 */
  updateOrgIcon: (url: string | null) => void
  /** バナー URL を更新（banner-updated 由来）。 */
  updateOrgBanner: (url: string | null) => void
}

/**
 * 組織詳細シェルが子タブへ共有するコンテキスト。
 */
export interface OrgShellContext {
  /** 組織本体（取得前は null）。 */
  org: ComputedRef<OrgDetail | null>
  /** 組織表示名（nickname1 優先 → name）。 */
  displayName: ComputedRef<string>
  /** 自分の実効ロール名（取得失敗/未所属時は null）。 */
  roleName: Ref<string | null>
  /** 管理権限（ADMIN / SYSTEM_ADMIN）を持つか。 */
  isAdmin: ComputedRef<boolean>
  /** 管理者または副管理者か。 */
  isAdminOrDeputy: ComputedRef<boolean>
  /** ScopeDashboard に渡す実効 viewerRole（レンズ OFF 時は MEMBER）。 */
  effectiveViewerRole: ComputedRef<ViewerRole>
  /** 管理者レンズ状態（true=管理者ビュー / false=メンバービュー）。 */
  adminLens: Ref<boolean>
  /** ウィジェット可視性設定（ScopeDashboard の visibility-map 用）。 */
  widgetVisibilitySettings: Ref<WidgetVisibilitySetting[]>
  /** 所属チーム一覧（所属チームタブ用）。 */
  orgTeams: Ref<OrgTeam[]>
  /** 祖先組織（パンくず・基本情報タブ用）。 */
  ancestors: Ref<AncestorOrganization[]>
  /** 直近の子組織（下位組織タブ用）。 */
  children: Ref<ChildOrganization[]>
  /** 子組織の追加読み込みが残っているか。 */
  childrenHasNext: Ref<boolean>
  /** 子組織のロード中フラグ。 */
  hierarchyLoading: Ref<boolean>
  /** 子組織を読み込む（reset=false で追加読み込み）。 */
  fetchChildren: (reset?: boolean) => Promise<void>
  /** 権限グループ一覧（権限グループタブ用）。 */
  permissionGroups: Ref<OrgPermissionGroup[]>
  /** 下位組織タブを表示するか（子 0 件かつ非 ADMIN なら隠す）。 */
  showChildrenTab: ComputedRef<boolean>
  /** 組織内チーム検索ページへの導線を表示するか。 */
  showTeamSearchLink: ComputedRef<boolean>
  /** orgType コード → 日本語ラベルの対応表。 */
  orgTypeLabel: Record<string, string>
  /** 組織再取得（状態同期用）。 */
  refresh: () => Promise<void>
  /** 親 org ref を部分更新するミューテータ群。 */
  mutators: OrgShellMutators
}

/**
 * 組織詳細コンテキストの InjectionKey。親 `pages/organizations/[slug].vue` のみが provide する。
 */
export const OrgShellContextKey: InjectionKey<OrgShellContext> = Symbol('OrgShellContext')

/**
 * 親 `pages/organizations/[slug].vue` でコンテキストを provide する。
 */
export function provideOrgShellContext(ctx: OrgShellContext): void {
  provide(OrgShellContextKey, ctx)
}

/**
 * 子タブから組織詳細コンテキストを取得する。
 *
 * @throws 親 `pages/organizations/[slug].vue`（= `<NuxtPage>` の外）で呼ばれた場合に例外を投げる。
 */
export function useOrgShellContext(): OrgShellContext {
  const ctx = inject(OrgShellContextKey)
  if (!ctx) {
    throw new Error(
      'OrgShellContext not provided. Render inside pages/organizations/[slug].vue (<NuxtPage>).',
    )
  }
  return ctx
}
