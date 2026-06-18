<script setup lang="ts">
/**
 * F10.1.1 P2a — チーム管理コンソール L2 ハブ（骨格）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/01_console_routes.md §2
 *
 * <p>アクセス制御は `admin-console` ミドルウェア（§5）で行う（取得失敗と権限不足を区別し、
 * 権限不足はスコープトップへリダイレクト＋トースト・取得失敗は再試行画面）。
 * ページ本体では二重防御として `useRoleAccess` の `isAdminOrDeputy` でカード表示を制御する。
 *
 * <p>各カードは該当する既存ルートへ導線を張る（P2a スコープ）。承認待ち（`/admin/approvals`）は
 * 未整備（P4）のため「近日公開」プレースホルダのままだが、P2b で横断集約 API
 * （`admin-action-required`・03）を消費して件数バッジを点火する。集計失敗（degraded）の
 * ドメインがある場合は 0 件と混同せず「一部集計できず」を明示し、API 自体の取得失敗は
 * 握りつぶさずバッジ非表示＋小さな注記で穏当に扱う（03 §4.3）。
 */
definePageMeta({
  layout: 'team',
  middleware: ['auth', 'admin-console'],
})

interface AdminConsoleCard {
  key: string
  titleKey: string
  descKey: string
  icon: string
  /** 既存ルートが整備済みのカードのみ to を持つ。未整備カードは to: null（プレースホルダ）。 */
  to: string | null
  /** 横断承認待ちバッジを点火するカードか（approvals のみ true）。 */
  showApprovalsBadge?: boolean
}

const route = useRoute()
const { t } = useI18n()

const teamSlug = computed(() => String(route.params.slug))
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const { getAdminActionRequired } = useScopeTabApi()

// P2b: 横断承認待ち集約のバッジ状態。
// pending: total_pending（degraded ドメインは加算されない）。
// degraded: いずれかのドメインが集計失敗（0 件と区別して注記）。
// fetchFailed: API 自体の取得失敗（症状を隠さず非表示＋注記）。
const approvalsPending = ref<number | null>(null)
const approvalsDegraded = ref(false)
const approvalsFetchFailed = ref(false)

async function loadAdminActionRequired() {
  try {
    // バッジ用途のため preview_size=0（件数のみ・プレビュー不要）。
    const summary = await getAdminActionRequired('TEAM', teamSlug.value, 0)
    approvalsPending.value = summary.totalPending
    approvalsDegraded.value = summary.domains.some((d) => d.degraded)
    approvalsFetchFailed.value = false
  } catch {
    // 取得失敗は握りつぶさず、バッジ非表示＋注記で穏当に表示する（症状を隠さない）。
    approvalsPending.value = null
    approvalsDegraded.value = false
    approvalsFetchFailed.value = true
  }
}

// 二重防御用にページ本体でも権限を解決（ミドルウェアが本丸・ここは表示制御）。
onMounted(() => {
  void loadPermissions()
  void loadAdminActionRequired()
})

const base = computed(() => `/teams/${teamSlug.value}`)

// 01 §2.3 表示カードと導線（team）。リンク先は実在ルートに合わせる（P2a）。
const cards = computed<AdminConsoleCard[]>(() => [
  {
    key: 'reservations',
    titleKey: 'adminConsole.cards.reservations.title',
    descKey: 'adminConsole.cards.reservations.desc',
    icon: 'pi pi-calendar-clock',
    to: `${base.value}/reservations`,
  },
  {
    key: 'budget',
    titleKey: 'adminConsole.cards.budget.title',
    descKey: 'adminConsole.cards.budget.desc',
    icon: 'pi pi-wallet',
    to: `${base.value}/budget`,
  },
  {
    key: 'approvals',
    titleKey: 'adminConsole.cards.approvals.title',
    descKey: 'adminConsole.cards.approvals.desc',
    icon: 'pi pi-inbox',
    // /admin/approvals は P4 で整備。P2b でも導線は未整備（リンクしない）だが件数バッジは点火する。
    to: null,
    showApprovalsBadge: true,
  },
  {
    key: 'members',
    titleKey: 'adminConsole.cards.members.title',
    descKey: 'adminConsole.cards.members.desc',
    icon: 'pi pi-users',
    to: `${base.value}/member-cards`,
  },
  {
    key: 'settings',
    titleKey: 'adminConsole.cards.settings.title',
    descKey: 'adminConsole.cards.settings.desc',
    icon: 'pi pi-cog',
    to: `${base.value}/settings/shift`,
  },
])
</script>

<template>
  <div class="space-y-6 p-4 md:p-6">
    <template v-if="isAdminOrDeputy">
      <header>
        <h1 class="text-2xl font-bold">
          {{ t('adminConsole.team.title') }}
        </h1>
        <p class="mt-1 text-sm text-surface-600 dark:text-surface-400">
          {{ t('adminConsole.team.subtitle') }}
        </p>
      </header>

      <section class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <component
          :is="card.to ? 'NuxtLink' : 'div'"
          v-for="card in cards"
          :key="card.key"
          :to="card.to ?? undefined"
          :class="[
            'relative flex flex-col rounded-xl border p-5 shadow-sm transition',
            card.to
              ? 'border-surface-200 bg-white hover:border-primary-400 hover:shadow-md dark:border-surface-700 dark:bg-surface-900'
              : 'cursor-default border-dashed border-surface-300 bg-surface-50 dark:border-surface-600 dark:bg-surface-800/40',
          ]"
        >
          <!-- 横断承認待ちバッジ（P2b 点火・approvals カードのみ）。
               件数 > 0 のとき赤バッジ。degraded のときは 0 件と区別する注記。
               取得失敗は握りつぶさず注記で穏当に表示する（03 §4.3）。 -->
          <span
            v-if="card.showApprovalsBadge && approvalsPending !== null && approvalsPending > 0"
            class="absolute right-3 top-3 inline-flex min-w-[1.5rem] items-center justify-center rounded-full bg-red-500 px-1.5 py-0.5 text-xs font-semibold text-white"
          >
            {{ approvalsPending }}
          </span>
          <span
            v-else-if="card.showApprovalsBadge && approvalsDegraded"
            class="absolute right-3 top-3 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[0.7rem] font-medium text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
          >
            <i class="pi pi-exclamation-triangle text-[0.7rem]" aria-hidden="true" />
            {{ t('adminConsole.approvalsBadge.degraded') }}
          </span>
          <span
            v-else-if="card.showApprovalsBadge && approvalsFetchFailed"
            class="absolute right-3 top-3 text-[0.7rem] font-medium text-surface-400 dark:text-surface-500"
          >
            {{ t('adminConsole.approvalsBadge.fetchFailed') }}
          </span>

          <div class="flex items-center gap-2">
            <i :class="[card.icon, 'text-lg text-primary-600 dark:text-primary-400']" aria-hidden="true" />
            <h2 class="text-base font-semibold">
              {{ t(card.titleKey) }}
            </h2>
          </div>
          <p class="mt-2 flex-1 text-sm text-surface-600 dark:text-surface-400">
            {{ t(card.descKey) }}
          </p>
          <span
            v-if="card.to"
            class="mt-3 inline-block text-sm font-medium text-primary-600 dark:text-primary-400"
          >
            &rarr;
          </span>
          <span
            v-else
            class="mt-3 inline-block text-xs font-medium text-surface-400 dark:text-surface-500"
          >
            {{ t('adminConsole.comingSoon') }}
          </span>
        </component>
      </section>
    </template>
  </div>
</template>
