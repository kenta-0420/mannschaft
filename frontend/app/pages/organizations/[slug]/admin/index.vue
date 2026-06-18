<script setup lang="ts">
/**
 * F10.1.1 P2a — 組織管理コンソール L2 ハブ（骨格・index のみ新設）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/01_console_routes.md §2
 *
 * <p>`/organizations/[slug]/admin/point-cards/` は既存（F18 Phase2）。本 index はそのハブのトップを新設し、
 * point-cards へはカードで導線を張る（point-cards 自体の認可作法は据え置き・05 §6）。
 *
 * <p>アクセス制御は `admin-console` ミドルウェア（§5）で行う（取得失敗と権限不足を区別）。
 * ページ本体では二重防御として `useRoleAccess` の `isAdminOrDeputy` でカード表示を制御する。
 *
 * <p>承認待ち（`/admin/approvals`）は未整備（P4）だが、P2b で横断集約 API
 * （`admin-action-required`・03）を消費して件数バッジを点火する。組織スコープは PAYMENT
 * （未収請求）1 ドメインのみ集約される（03 §3.2）。集計失敗（degraded）は 0 件と区別表示し、
 * API 取得失敗は握りつぶさず注記で穏当に扱う（03 §4.3）。
 */
definePageMeta({
  layout: 'organization',
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

const orgSlug = computed(() => String(route.params.slug))
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { getAdminActionRequired } = useScopeTabApi()

// P2b: 横断承認待ち集約のバッジ状態（team ハブと同方針・03 §4.3）。
const approvalsPending = ref<number | null>(null)
const approvalsDegraded = ref(false)
const approvalsFetchFailed = ref(false)

async function loadAdminActionRequired() {
  try {
    // バッジ用途のため preview_size=0（件数のみ）。
    const summary = await getAdminActionRequired('ORGANIZATION', orgSlug.value, 0)
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

const base = computed(() => `/organizations/${orgSlug.value}`)

// 01 §2.3 表示カードと導線（org）。リンク先は実在ルートに合わせる（P2a）。
const cards = computed<AdminConsoleCard[]>(() => [
  {
    key: 'budget',
    titleKey: 'adminConsole.cards.budget.title',
    descKey: 'adminConsole.cards.budget.desc',
    icon: 'pi pi-wallet',
    to: `${base.value}/budget`,
  },
  {
    key: 'payments',
    titleKey: 'adminConsole.cards.payments.title',
    descKey: 'adminConsole.cards.payments.desc',
    icon: 'pi pi-credit-card',
    to: `${base.value}/payments`,
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
    to: `${base.value}/settings/faq-settings`,
  },
  {
    key: 'pointCards',
    titleKey: 'adminConsole.cards.pointCards.title',
    descKey: 'adminConsole.cards.pointCards.desc',
    icon: 'pi pi-ticket',
    to: `${base.value}/admin/point-cards`,
  },
])
</script>

<template>
  <div class="space-y-6 p-4 md:p-6">
    <template v-if="isAdminOrDeputy">
      <header>
        <h1 class="text-2xl font-bold">
          {{ t('adminConsole.organization.title') }}
        </h1>
        <p class="mt-1 text-sm text-surface-600 dark:text-surface-400">
          {{ t('adminConsole.organization.subtitle') }}
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
