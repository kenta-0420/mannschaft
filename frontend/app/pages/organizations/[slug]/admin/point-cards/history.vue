<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 履歴ページ（スタンプ + 残高の 2 タブ）。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.1 / §12.2
 *
 * <p>組織配下の全プロバイダーの押印履歴・残高変動履歴を別タブで一覧する。
 * 既存のスタンプ履歴ロジックは {@code historyTab === 'stamp'} で維持。
 */
import type { BalanceEventResponse, OrgPointCardProvider, StampEventResponse } from '~/types/orgPointCard'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgStore = useOrganizationStore()
const orgSlug = computed(() => String(route.params.slug))

const api = useOrgWalletApi(() => orgSlug.value)

const myOrg = computed(() =>
  orgStore.myOrganizations.find(o => String(o.id) === orgSlug.value),
)
const canAccess = computed(() =>
  myOrg.value?.role === 'ADMIN'
  || myOrg.value?.role === 'SYSTEM_ADMIN'
  || myOrg.value?.role === 'DEPUTY_ADMIN',
)

type HistoryTab = 'stamp' | 'balance'
const historyTab = ref<HistoryTab>('stamp')

const providers = ref<OrgPointCardProvider[]>([])
const selectedProviderId = ref<string | ''>('')

// ─── スタンプ履歴 ────────────────────────────────────────────
const stamps = ref<StampEventResponse[]>([])
const stampLoading = ref(false)
const stampPage = ref(0)
const stampSize = ref(20)
const stampTotalElements = ref(0)
const stampTotalPages = ref(0)

// ─── 残高履歴 ────────────────────────────────────────────────
const balanceEvents = ref<BalanceEventResponse[]>([])
const balanceLoading = ref(false)
const balancePage = ref(0)
const balanceSize = ref(20)
const balanceTotalElements = ref(0)
const balanceTotalPages = ref(0)

async function fetchProviders() {
  try {
    providers.value = await api.listProviders(false)
  } catch (e) {
    console.error('[history] listProviders failed', e)
  }
}

async function fetchStamps() {
  stampLoading.value = true
  try {
    const res = await api.listOrgStamps({
      providerId: selectedProviderId.value || undefined,
      page: stampPage.value,
      size: stampSize.value,
    })
    stamps.value = res.content
    stampTotalElements.value = res.totalElements
    stampTotalPages.value = res.totalPages
  } catch (e) {
    console.error('[history] listOrgStamps failed', e)
    stamps.value = []
  } finally {
    stampLoading.value = false
  }
}

async function fetchBalanceEvents() {
  balanceLoading.value = true
  try {
    const res = await api.listOrgBalanceEvents({
      providerId: selectedProviderId.value || undefined,
      page: balancePage.value,
      size: balanceSize.value,
    })
    balanceEvents.value = res.content
    balanceTotalElements.value = res.totalElements
    balanceTotalPages.value = res.totalPages
  } catch (e) {
    console.error('[history] listOrgBalanceEvents failed', e)
    balanceEvents.value = []
  } finally {
    balanceLoading.value = false
  }
}

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  if (canAccess.value) {
    await fetchProviders()
    await fetchStamps()
  }
})

// プロバイダー絞り込みの変化は両タブのデータをリセットして再取得
watch(selectedProviderId, () => {
  stampPage.value = 0
  balancePage.value = 0
  void (historyTab.value === 'stamp' ? fetchStamps() : fetchBalanceEvents())
})

// タブ切替時にまだ取得していなければ初回取得する
watch(historyTab, async (tab) => {
  if (tab === 'balance' && balanceEvents.value.length === 0 && !balanceLoading.value) {
    await fetchBalanceEvents()
  }
})

function goPrevStamp() {
  if (stampPage.value > 0) {
    stampPage.value -= 1
    void fetchStamps()
  }
}

function goNextStamp() {
  if (stampPage.value < stampTotalPages.value - 1) {
    stampPage.value += 1
    void fetchStamps()
  }
}

function goPrevBalance() {
  if (balancePage.value > 0) {
    balancePage.value -= 1
    void fetchBalanceEvents()
  }
}

function goNextBalance() {
  if (balancePage.value < balanceTotalPages.value - 1) {
    balancePage.value += 1
    void fetchBalanceEvents()
  }
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <div
      v-if="!canAccess && myOrg"
      class="rounded-lg border border-amber-300 bg-amber-50 p-4 text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
    >
      <p class="font-semibold">
        {{ t('wallet.admin.errors.access_denied_title') }}
      </p>
    </div>

    <template v-else-if="canAccess">
      <header>
        <NuxtLink
          :to="`/organizations/${orgSlug}/admin/point-cards`"
          class="text-sm text-primary-600 hover:underline dark:text-primary-400"
        >
          &larr; {{ t('wallet.admin.actions.back') }}
        </NuxtLink>
        <h1 class="mt-2 text-2xl font-bold">
          {{ t('wallet.admin.history.title') }}
        </h1>
      </header>

      <!-- タブ切替 -->
      <div class="flex flex-wrap gap-2 border-b border-surface-200 pb-2 dark:border-surface-700" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="historyTab === 'stamp'"
          class="rounded px-3 py-1.5 text-sm font-medium"
          :class="historyTab === 'stamp' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
          @click="historyTab = 'stamp'"
        >
          {{ t('wallet.admin.history.tab_stamp') }}
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="historyTab === 'balance'"
          class="rounded px-3 py-1.5 text-sm font-medium"
          :class="historyTab === 'balance' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
          @click="historyTab = 'balance'"
        >
          {{ t('wallet.admin.history.tab_balance') }}
        </button>
      </div>

      <!-- 絞り込み（両タブ共通） -->
      <div class="flex flex-wrap items-center gap-2">
        <label for="filter-provider" class="text-sm font-medium">
          {{ t('wallet.admin.history.filter_provider') }}
        </label>
        <select
          id="filter-provider"
          v-model="selectedProviderId"
          class="rounded border border-surface-300 px-3 py-2 text-sm dark:border-surface-600 dark:bg-surface-800"
        >
          <option value="">
            {{ t('wallet.admin.history.all_providers') }}
          </option>
          <option v-for="p in providers" :key="p.id" :value="p.id">
            {{ p.displayName }}
          </option>
        </select>
        <span class="ml-auto text-sm text-surface-600 dark:text-surface-400">
          {{ t('wallet.admin.history.total_count', {
            count: historyTab === 'stamp' ? stampTotalElements : balanceTotalElements,
          }) }}
        </span>
      </div>

      <!-- スタンプ履歴 -->
      <template v-if="historyTab === 'stamp'">
        <StampHistoryTable :stamps="stamps" :loading="stampLoading" />

        <div v-if="stampTotalPages > 1" class="flex items-center justify-center gap-2">
          <button
            type="button"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
            :disabled="stampPage === 0 || stampLoading"
            @click="goPrevStamp"
          >
            &larr; {{ t('wallet.admin.actions.prev') }}
          </button>
          <span class="text-sm">
            {{ t('wallet.admin.history.page_indicator', { current: stampPage + 1, total: stampTotalPages }) }}
          </span>
          <button
            type="button"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
            :disabled="stampPage >= stampTotalPages - 1 || stampLoading"
            @click="goNextStamp"
          >
            {{ t('wallet.admin.actions.next') }} &rarr;
          </button>
        </div>
      </template>

      <!-- 残高履歴 -->
      <template v-else>
        <BalanceHistoryTable :events="balanceEvents" :loading="balanceLoading" />

        <div v-if="balanceTotalPages > 1" class="flex items-center justify-center gap-2">
          <button
            type="button"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
            :disabled="balancePage === 0 || balanceLoading"
            @click="goPrevBalance"
          >
            &larr; {{ t('wallet.admin.actions.prev') }}
          </button>
          <span class="text-sm">
            {{ t('wallet.admin.history.page_indicator', { current: balancePage + 1, total: balanceTotalPages }) }}
          </span>
          <button
            type="button"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
            :disabled="balancePage >= balanceTotalPages - 1 || balanceLoading"
            @click="goNextBalance"
          >
            {{ t('wallet.admin.actions.next') }} &rarr;
          </button>
        </div>
      </template>
    </template>
  </div>
</template>
