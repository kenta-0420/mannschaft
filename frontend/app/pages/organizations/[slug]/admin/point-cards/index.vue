<script setup lang="ts">
/**
 * F18 Phase 2 — 店主ダッシュボード（ハブ画面 + プロバイダー一覧）。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §8 UI/UX
 *
 * <p>3 つの大きいアクションカード（プロバイダー / 履歴 / 押印）と、稼働中プロバイダー一覧を出す。
 * アクセス制御: 組織 ADMIN / SYSTEM_ADMIN / DEPUTY_ADMIN のみ。それ以外は組織トップへリダイレクト。
 */
import type { OrgPointCardProvider } from '~/types/orgPointCard'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgStore = useOrganizationStore()
const orgSlug = computed(() => String(route.params.slug))

const api = useOrgWalletApi(() => orgSlug.value)

// ─── アクセス制御 ────────────────────────────────────────────
const myOrg = computed(() =>
  orgStore.myOrganizations.find(o => String(o.id) === orgSlug.value),
)
const canAccess = computed(() =>
  myOrg.value?.role === 'ADMIN'
  || myOrg.value?.role === 'SYSTEM_ADMIN'
  || myOrg.value?.role === 'DEPUTY_ADMIN',
)
const isAdminOnly = computed(() =>
  myOrg.value?.role === 'ADMIN' || myOrg.value?.role === 'SYSTEM_ADMIN',
)

// ─── 状態 ────────────────────────────────────────────────────
const providers = ref<OrgPointCardProvider[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

async function fetchProviders() {
  loading.value = true
  loadError.value = null
  try {
    providers.value = await api.listProviders(true)
  } catch (e) {
    console.error('[admin/point-cards] listProviders failed', e)
    loadError.value = t('wallet.admin.errors.load_failed')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 組織情報が未取得の場合はロード
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  // アクセス権チェック後フェッチ
  if (canAccess.value) {
    await fetchProviders()
  }
})

watch(canAccess, (v) => {
  if (v && providers.value.length === 0 && !loading.value) {
    void fetchProviders()
  }
})
</script>

<template>
  <div class="space-y-6 p-4 md:p-6">
    <!-- アクセス拒否 -->
    <div
      v-if="!canAccess && myOrg"
      class="rounded-lg border border-amber-300 bg-amber-50 p-4 text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
    >
      <p class="font-semibold">
        {{ t('wallet.admin.errors.access_denied_title') }}
      </p>
      <p class="mt-1 text-sm">
        {{ t('wallet.admin.errors.access_denied_body') }}
      </p>
    </div>

    <template v-else-if="canAccess">
      <!-- ヘッダ -->
      <header>
        <h1 class="text-2xl font-bold">
          {{ t('wallet.admin.page_title') }}
        </h1>
        <p class="mt-1 text-sm text-surface-600 dark:text-surface-400">
          {{ myOrg?.name }}
        </p>
      </header>

      <!-- 3 つのアクションカード -->
      <section class="grid gap-4 sm:grid-cols-3">
        <NuxtLink
          :to="`/organizations/${orgSlug}/admin/point-cards/providers/new`"
          class="flex flex-col rounded-xl border border-surface-200 bg-white p-5 shadow-sm transition hover:border-primary-400 hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
        >
          <h2 class="text-base font-semibold">
            {{ t('wallet.admin.tabs.providers') }}
          </h2>
          <p class="mt-2 flex-1 text-sm text-surface-600 dark:text-surface-400">
            {{ t('wallet.admin.cards.providers_desc') }}
          </p>
          <span class="mt-3 inline-block text-sm font-medium text-primary-600 dark:text-primary-400">
            &rarr;
          </span>
        </NuxtLink>

        <NuxtLink
          :to="`/organizations/${orgSlug}/admin/point-cards/history`"
          class="flex flex-col rounded-xl border border-surface-200 bg-white p-5 shadow-sm transition hover:border-primary-400 hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
        >
          <h2 class="text-base font-semibold">
            {{ t('wallet.admin.tabs.history') }}
          </h2>
          <p class="mt-2 flex-1 text-sm text-surface-600 dark:text-surface-400">
            {{ t('wallet.admin.cards.history_desc') }}
          </p>
          <span class="mt-3 inline-block text-sm font-medium text-primary-600 dark:text-primary-400">
            &rarr;
          </span>
        </NuxtLink>

        <NuxtLink
          :to="`/organizations/${orgSlug}/admin/point-cards/stamp`"
          class="flex flex-col rounded-xl border border-primary-300 bg-primary-50 p-5 shadow-sm transition hover:border-primary-500 hover:shadow-md dark:border-primary-700 dark:bg-primary-950"
        >
          <h2 class="text-base font-semibold text-primary-900 dark:text-primary-100">
            {{ t('wallet.admin.tabs.stamp') }}
          </h2>
          <p class="mt-2 flex-1 text-sm text-primary-800 dark:text-primary-200">
            {{ t('wallet.admin.cards.stamp_desc') }}
          </p>
          <span class="mt-3 inline-block text-sm font-semibold text-primary-700 dark:text-primary-300">
            {{ t('wallet.admin.cards.stamp_cta') }} &rarr;
          </span>
        </NuxtLink>
      </section>

      <!-- プロバイダー一覧 -->
      <section class="space-y-3">
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-semibold">
            {{ t('wallet.admin.providers.title') }}
          </h2>
          <NuxtLink
            v-if="isAdminOnly"
            :to="`/organizations/${orgSlug}/admin/point-cards/providers/new`"
            class="rounded bg-primary-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-primary-700"
          >
            {{ t('wallet.admin.providers.new') }}
          </NuxtLink>
        </div>

        <div v-if="loading" class="py-6 text-center text-surface-500">
          {{ t('wallet.admin.providers.loading') }}
        </div>
        <div v-else-if="loadError" class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300">
          {{ loadError }}
        </div>
        <div v-else-if="providers.length === 0" class="rounded border border-dashed border-surface-300 p-6 text-center text-surface-500 dark:border-surface-600">
          {{ t('wallet.admin.providers.empty') }}
        </div>

        <ul v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <li
            v-for="p in providers"
            :key="p.id"
            class="overflow-hidden rounded-lg border border-surface-200 bg-white shadow-sm dark:border-surface-700 dark:bg-surface-900"
          >
            <NuxtLink
              :to="`/organizations/${orgSlug}/admin/point-cards/providers/${p.id}`"
              class="block p-4 hover:bg-surface-50 dark:hover:bg-surface-800/50"
            >
              <div class="flex items-center gap-3">
                <div
                  class="h-10 w-10 flex-shrink-0 rounded-full"
                  :style="{ backgroundColor: p.brandColor ?? '#9ca3af' }"
                  aria-hidden="true"
                />
                <div class="min-w-0 flex-1">
                  <p class="truncate font-medium">
                    {{ p.displayName }}
                  </p>
                  <p class="truncate text-xs text-surface-500">
                    {{ p.code }}
                  </p>
                </div>
                <span
                  v-if="p.isActive"
                  class="rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700 dark:bg-green-900 dark:text-green-300"
                >
                  {{ t('wallet.admin.providers.active') }}
                </span>
                <span
                  v-else
                  class="rounded-full bg-surface-200 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
                >
                  {{ t('wallet.admin.providers.inactive') }}
                </span>
              </div>
            </NuxtLink>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
