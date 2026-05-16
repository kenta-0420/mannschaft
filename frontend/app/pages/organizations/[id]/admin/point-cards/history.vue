<script setup lang="ts">
/**
 * F18 Phase 2 — スタンプ押印履歴ページ（証拠ログ）。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.2「証拠ログ」
 *
 * <p>組織配下の全プロバイダーの押印履歴をページネーション付きで一覧する。
 * プロバイダー絞り込みドロップダウンと、押印者・delta・memo・cardId（コピー）を提供。
 */
import type { OrgPointCardProvider, StampEventResponse } from '~/types/orgPointCard'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgStore = useOrganizationStore()
const orgId = computed(() => Number(route.params.id))

const api = useOrgWalletApi(() => orgId.value)

const myOrg = computed(() =>
  orgStore.myOrganizations.find(o => o.id === orgId.value),
)
const canAccess = computed(() =>
  myOrg.value?.role === 'ADMIN'
  || myOrg.value?.role === 'SYSTEM_ADMIN'
  || myOrg.value?.role === 'DEPUTY_ADMIN',
)

const providers = ref<OrgPointCardProvider[]>([])
const selectedProviderId = ref<string | ''>('')

const stamps = ref<StampEventResponse[]>([])
const loading = ref(false)
const page = ref(0)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)

async function fetchProviders() {
  try {
    providers.value = await api.listProviders(false)
  } catch (e) {
    console.error('[history] listProviders failed', e)
  }
}

async function fetchHistory() {
  loading.value = true
  try {
    const res = await api.listOrgStamps({
      providerId: selectedProviderId.value || undefined,
      page: page.value,
      size: size.value,
    })
    stamps.value = res.content
    totalElements.value = res.totalElements
    totalPages.value = res.totalPages
  } catch (e) {
    console.error('[history] listOrgStamps failed', e)
    stamps.value = []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  if (canAccess.value) {
    await fetchProviders()
    await fetchHistory()
  }
})

watch(selectedProviderId, () => {
  page.value = 0
  void fetchHistory()
})

function goPrev() {
  if (page.value > 0) {
    page.value -= 1
    void fetchHistory()
  }
}

function goNext() {
  if (page.value < totalPages.value - 1) {
    page.value += 1
    void fetchHistory()
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
          :to="`/organizations/${orgId}/admin/point-cards`"
          class="text-sm text-primary-600 hover:underline dark:text-primary-400"
        >
          &larr; {{ t('wallet.admin.actions.back') }}
        </NuxtLink>
        <h1 class="mt-2 text-2xl font-bold">
          {{ t('wallet.admin.history.title') }}
        </h1>
      </header>

      <!-- 絞り込み -->
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
          {{ t('wallet.admin.history.total_count', { count: totalElements }) }}
        </span>
      </div>

      <!-- テーブル -->
      <StampHistoryTable :stamps="stamps" :loading="loading" />

      <!-- ページネーション -->
      <div v-if="totalPages > 1" class="flex items-center justify-center gap-2">
        <button
          type="button"
          class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
          :disabled="page === 0 || loading"
          @click="goPrev"
        >
          &larr; {{ t('wallet.admin.actions.prev') }}
        </button>
        <span class="text-sm">
          {{ t('wallet.admin.history.page_indicator', { current: page + 1, total: totalPages }) }}
        </span>
        <button
          type="button"
          class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 disabled:opacity-50 dark:border-surface-600 dark:hover:bg-surface-800"
          :disabled="page >= totalPages - 1 || loading"
          @click="goNext"
        >
          {{ t('wallet.admin.actions.next') }} &rarr;
        </button>
      </div>
    </template>
  </div>
</template>
