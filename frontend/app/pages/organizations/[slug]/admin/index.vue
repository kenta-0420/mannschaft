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
  /** P2b で点火する要対応件数バッジ枠。P2a では常に未取得（null）＝非表示フォールバック。 */
  pendingCount: number | null
}

const route = useRoute()
const { t } = useI18n()

const orgSlug = computed(() => String(route.params.slug))
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

// 二重防御用にページ本体でも権限を解決（ミドルウェアが本丸・ここは表示制御）。
onMounted(() => {
  void loadPermissions()
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
    pendingCount: null,
  },
  {
    key: 'payments',
    titleKey: 'adminConsole.cards.payments.title',
    descKey: 'adminConsole.cards.payments.desc',
    icon: 'pi pi-credit-card',
    to: `${base.value}/payments`,
    pendingCount: null,
  },
  {
    key: 'approvals',
    titleKey: 'adminConsole.cards.approvals.title',
    descKey: 'adminConsole.cards.approvals.desc',
    icon: 'pi pi-inbox',
    // /admin/approvals は P4 で整備。P2a ではプレースホルダ（リンクしない）。
    to: null,
    // P2b で集約 API のサマリを充填する。P2a は未取得（null）＝バッジ非表示。
    pendingCount: null,
  },
  {
    key: 'members',
    titleKey: 'adminConsole.cards.members.title',
    descKey: 'adminConsole.cards.members.desc',
    icon: 'pi pi-users',
    to: `${base.value}/member-cards`,
    pendingCount: null,
  },
  {
    key: 'settings',
    titleKey: 'adminConsole.cards.settings.title',
    descKey: 'adminConsole.cards.settings.desc',
    icon: 'pi pi-cog',
    to: `${base.value}/settings/faq-settings`,
    pendingCount: null,
  },
  {
    key: 'pointCards',
    titleKey: 'adminConsole.cards.pointCards.title',
    descKey: 'adminConsole.cards.pointCards.desc',
    icon: 'pi pi-ticket',
    to: `${base.value}/admin/point-cards`,
    pendingCount: null,
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
          <!-- 要対応件数バッジ枠（P2b で点火・件数があるときのみ表示） -->
          <span
            v-if="card.pendingCount !== null && card.pendingCount > 0"
            class="absolute right-3 top-3 inline-flex min-w-[1.5rem] items-center justify-center rounded-full bg-red-500 px-1.5 py-0.5 text-xs font-semibold text-white"
          >
            {{ card.pendingCount }}
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
