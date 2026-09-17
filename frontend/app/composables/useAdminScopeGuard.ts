/**
 * `/admin/*` 配下の横断ルート向け直リンク防御（CMP-260917-1351 課題B）。
 *
 * これらのページはスコープ配下（`organizations/[slug]/...`）ではなく横断ルートに置かれており、
 * `to.params.slug` が存在しないため `middleware/org-role-guard.ts`（slug ベース）をそのまま
 * 適用できない。スコープの表し方も呼び出し元によって2系統ある:
 *  - `useScopeStore`（アプリ全体で選択中のスコープ。既定）… receipts / receipt-settings /
 *    line-settings / sns-settings / schedule-settings / bulletin-categories
 *  - URL クエリ `?scope=teams|organizations&scopeId=N`（`vendors/index.vue` のみ、他スコープを
 *    横断表示できる設計のため独立）
 *
 * いずれも `scopeId` は数値 ID（`useScopeStore` 由来）であり、権限 API
 * `/api/v1/{organizations|teams}/{slug}/me/permissions` は slug でしか解決できない
 * （`OrganizationService#resolveOrgId`）ため、素朴に数値 ID を渡すと 404 になる。
 * ここでは `useOrganizationStore` / `useTeamStore` が既に保持する「自分の所属一覧＋各スコープの
 * role」（`/api/v1/me/organizations` `/api/v1/me/teams`）を使う。role は確定済みで返るため、
 * 追加の権限 API 呼び出しや slug 解決が不要（`useShiftBudgetAdminAccess.ts` の数値ID→slug 解決
 * より単純な経路）。
 *
 * 方針は `middleware/admin-console.ts` / `middleware/org-role-guard.ts` と揃える
 * （権限不足はダッシュボードへ誘導＋トースト、`/login` へは飛ばさない）。
 * 個人スコープ（team/organization どちらでもない）はこのガードの対象外とする
 * （各ページ自身が「対象外」の案内を出す設計のため、ここで横取りしない）。
 */

const ROLE_RANK: Record<string, number> = {
  GUEST: -1,
  SUPPORTER: -1,
  MEMBER: 0,
  DEPUTY_ADMIN: 1,
  ADMIN: 2,
  SYSTEM_ADMIN: 2,
}

export type AdminScopeMinRole = 'ADMIN' | 'DEPUTY_ADMIN'

/** 所属一覧に role が存在し、かつ要求ロール以上か。未所属（found なし）は常に false。 */
function meetsMinRole(role: string | undefined, minRole: AdminScopeMinRole): boolean {
  if (!role) return false
  return (ROLE_RANK[role] ?? -1) >= ROLE_RANK[minRole]
}

export interface AdminScopeGuardSource {
  /** 'team' | 'organization' | 'personal' など。team/organization 以外は判定対象外。 */
  scopeType: Ref<string> | ComputedRef<string>
  /** 数値スコープID（文字列化済み）。空文字は「未確定」として扱う。 */
  scopeId: Ref<string> | ComputedRef<string>
}

/**
 * 要求ロールを満たさなければダッシュボードへ戻す（トースト付き）。
 * ページの `<script setup>` 冒頭で呼ぶこと。
 *
 * @param minRole 要求する最低ロール（既定 DEPUTY_ADMIN）
 * @param source 明示的なスコープ源（省略時は `useScopeStore().current` を使う）
 */
export function useAdminScopeGuard(
  minRole: AdminScopeMinRole = 'DEPUTY_ADMIN',
  source?: AdminScopeGuardSource,
) {
  const scopeStore = useScopeStore()
  const scopeType = source?.scopeType ?? computed(() => scopeStore.current.type)
  const scopeId = source?.scopeId ?? computed(() => scopeStore.current.id ?? '')

  const organizationStore = useOrganizationStore()
  const teamStore = useTeamStore()
  const nuxtApp = useNuxtApp()
  const { t } = useI18n()

  function deny() {
    const toast = nuxtApp.$toast as
      | { add: (opts: Record<string, unknown>) => void }
      | undefined
    if (toast) {
      toast.add({
        severity: 'error',
        summary: t('adminConsole.middleware.accessDeniedTitle'),
        detail: t('adminConsole.middleware.accessDeniedBody'),
        life: 5000,
      })
    }
    navigateTo('/dashboard')
  }

  async function check() {
    const type = scopeType.value
    const id = scopeId.value
    // 個人スコープ・未確定はこのガードの対象外（ページ自身の案内に任せる）。
    if (!id || (type !== 'organization' && type !== 'team')) return

    if (type === 'organization') {
      if (organizationStore.myOrganizations.length === 0) {
        await organizationStore.fetchMyOrganizations()
      }
      const found = organizationStore.myOrganizations.find(o => String(o.id) === id)
      if (!meetsMinRole(found?.role, minRole)) deny()
    }
    else {
      if (teamStore.myTeams.length === 0) {
        await teamStore.fetchMyTeams()
      }
      const found = teamStore.myTeams.find(team => String(team.id) === id)
      if (!meetsMinRole(found?.role, minRole)) deny()
    }
  }

  onMounted(() => {
    void check()
  })
  watch([scopeType, scopeId], () => { void check() })
}
