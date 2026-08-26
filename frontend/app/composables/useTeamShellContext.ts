import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { ViewerRole, WidgetVisibilitySetting } from '~/types/dashboard'
import type { TeamResponse } from '~/types/team'

/**
 * チーム詳細「永続シェル」用 provide/inject コンテキスト。
 *
 * # 背景（村方式の踏襲）
 *  従来チーム詳細は `pages/teams/[slug]/index.vue` がヘッダ・8 タブ・全パネルを
 *  抱え込み、ウィジェット押下でフルページ遷移して文脈が消えていた。
 *  これを村（`useVillageContext` / `pages/villages/[id].vue`）と同じ
 *  「永続シェル方式（SPA）」に改める。
 *
 *  親レイアウトルート `pages/teams/[slug].vue` でチーム・権限・可視性設定を 1 度だけ
 *  解決し、`<NuxtPage>` の子タブへ provide する。子タブ（index / info / members …）は
 *  本コンテキストを inject し、権限で二重ガードしつつ各パネルへ結線する。
 *
 * # 親子関係
 *  - 親: `pages/teams/[slug].vue`（TeamPageHeader 常駐 + チーム取得 + provide）
 *  - 子: `pages/teams/[slug]/*.vue`（各タブのパネル本体のみ）
 *
 * # 設計上の配慮（useVillageContext を範とする）
 *  - `inject(key)!` の非 null アサーション散在を避けるため、未 provide 時に明示的に
 *    throw する `useTeamShellContext()` アクセサを公開する。
 *  - TeamDetailInfo は icon/banner/mapEmbed/regionCodes を親へ双方向通知するため、
 *    親が保持する team ref を子から更新できるミューテータ（updateTeamMedia など）も
 *    コンテキストに含める。
 */

/** 親が保持するチーム ref を子から部分更新するためのミューテータ群。 */
export interface TeamShellMutators {
  /** アイコン URL を更新（TeamPageHeader / TeamDetailInfo の icon-updated 由来）。 */
  updateTeamIcon: (url: string | null) => void
  /** バナー URL を更新（banner-updated 由来）。 */
  updateTeamBanner: (url: string | null) => void
  /** Google Maps 埋め込み URL を更新（TeamDetailInfo の updated:mapEmbedUrl 由来）。 */
  updateTeamMapEmbed: (url: string | null) => void
  /** 所在地コード（都道府県/市区町村）を更新（updated:regionCodes 由来）。 */
  updateTeamRegionCodes: (prefectureCode: string | null, cityCode: string | null) => void
  /** 予約枠の基準タイムゾーンを更新（updated:timezone 由来）。 */
  updateTeamTimezone: (timezone: string) => void
}

/**
 * チーム詳細シェルが子タブへ共有するコンテキスト。
 */
export interface TeamShellContext {
  /** チーム本体（取得前は null）。子は読み取りで参照する。 */
  team: ComputedRef<TeamResponse | null>
  /** チーム表示名（nickname1 優先 → name）。 */
  displayName: ComputedRef<string>
  /** 自分の実効ロール名（取得失敗/未所属時は null）。 */
  roleName: Ref<string | null>
  /** 管理権限（ADMIN / SYSTEM_ADMIN）を持つか。 */
  isAdmin: ComputedRef<boolean>
  /** 管理者または副管理者か。 */
  isAdminOrDeputy: ComputedRef<boolean>
  /**
   * ScopeDashboard に渡す実効 viewerRole。
   * 管理者レンズ OFF 時は 'MEMBER'、ON 時は roleName（未確定は 'PUBLIC'）。
   */
  effectiveViewerRole: ComputedRef<ViewerRole>
  /** 管理者レンズ状態（true=管理者ビュー / false=メンバービュー）。 */
  adminLens: Ref<boolean>
  /** ウィジェット可視性設定（ScopeDashboard の visibility-map 用）。 */
  widgetVisibilitySettings: Ref<WidgetVisibilitySetting[]>
  /** 予約モジュールが有効か（予約タブ表示条件）。 */
  reservationEnabled: Ref<boolean>
  /** template コード → 日本語ラベルの対応表。 */
  templateLabel: Record<string, string>
  /** visibility コード → 日本語ラベルの対応表。 */
  visibilityLabel: Record<string, string>
  /** チーム再取得（状態同期用）。 */
  refresh: () => Promise<void>
  /** 親 team ref を部分更新するミューテータ群。 */
  mutators: TeamShellMutators
}

/**
 * チーム詳細コンテキストの InjectionKey。親 `pages/teams/[slug].vue` のみが provide する。
 */
export const TeamShellContextKey: InjectionKey<TeamShellContext> = Symbol('TeamShellContext')

/**
 * 親 `pages/teams/[slug].vue` でコンテキストを provide する。
 */
export function provideTeamShellContext(ctx: TeamShellContext): void {
  provide(TeamShellContextKey, ctx)
}

/**
 * 子タブからチーム詳細コンテキストを取得する。
 *
 * @throws 親 `pages/teams/[slug].vue`（= `<NuxtPage>` の外）で呼ばれた場合に例外を投げる。
 */
export function useTeamShellContext(): TeamShellContext {
  const ctx = inject(TeamShellContextKey)
  if (!ctx) {
    throw new Error(
      'TeamShellContext not provided. Render inside pages/teams/[slug].vue (<NuxtPage>).',
    )
  }
  return ctx
}
