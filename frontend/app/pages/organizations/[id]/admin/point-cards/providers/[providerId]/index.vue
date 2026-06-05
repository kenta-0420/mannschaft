<script setup lang="ts">
/**
 * F18 Phase 2 — プロバイダー詳細・編集ページ。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §12.1
 *
 * <p>詳細表示 / 編集モード切替 / 顧客向け QR 表示 / 停止（DELETE）を担う。
 * 停止操作は ADMIN / SYSTEM_ADMIN のみ可能（DEPUTY_ADMIN には UI から隠す）。
 */
import type {
  CreateOrgProviderRequest,
  CustomerQrResponse,
  OrgPointCardProvider,
  UpdateOrgProviderRequest,
} from '~/types/orgPointCard'
import type { FetchError } from 'ofetch'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgStore = useOrganizationStore()
const orgId = computed(() => String(route.params.id))
const providerId = computed(() => String(route.params.providerId))

const api = useOrgWalletApi(() => orgId.value)

const myOrg = computed(() =>
  orgStore.myOrganizations.find(o => String(o.id) === orgId.value),
)
const canAccess = computed(() =>
  myOrg.value?.role === 'ADMIN'
  || myOrg.value?.role === 'SYSTEM_ADMIN'
  || myOrg.value?.role === 'DEPUTY_ADMIN',
)
const isAdminOnly = computed(() =>
  myOrg.value?.role === 'ADMIN' || myOrg.value?.role === 'SYSTEM_ADMIN',
)

const provider = ref<OrgPointCardProvider | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const editing = ref(false)
const submitting = ref(false)
const submitError = ref<string | null>(null)

// 削除モーダル
const deactivateConfirmOpen = ref(false)
const deactivating = ref(false)

// 顧客 QR モーダル
const qr = ref<CustomerQrResponse | null>(null)
const qrModalOpen = ref(false)
const qrLoading = ref(false)

async function fetchProvider() {
  loading.value = true
  loadError.value = null
  try {
    provider.value = await api.getProvider(providerId.value)
  } catch (e) {
    console.error('[providers/[providerId]] getProvider failed', e)
    loadError.value = t('wallet.admin.errors.load_failed')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  if (canAccess.value) {
    await fetchProvider()
  }
})

async function onSaveEdit(body: CreateOrgProviderRequest | UpdateOrgProviderRequest) {
  // mode='edit' で ProviderForm から来る body は UpdateOrgProviderRequest 互換（全フィールド任意）。
  const patchBody: UpdateOrgProviderRequest = {
    displayName: body.displayName,
    brandColor: body.brandColor,
    logoUrl: body.logoUrl,
    cardNumberRegex: body.cardNumberRegex,
    cardNumberLengthHint: body.cardNumberLengthHint,
  }
  submitting.value = true
  submitError.value = null
  try {
    provider.value = await api.updateProvider(providerId.value, patchBody)
    editing.value = false
  } catch (e) {
    const fe = e as FetchError<{ message?: string }>
    submitError.value = fe.data?.message ?? t('wallet.admin.errors.save_failed')
    console.error('[providers/[providerId]] updateProvider failed', e)
  } finally {
    submitting.value = false
  }
}

async function onDeactivate() {
  deactivating.value = true
  try {
    await api.deactivateProvider(providerId.value)
    await navigateTo(`/organizations/${orgId.value}/admin/point-cards`)
  } catch (e) {
    console.error('[providers/[providerId]] deactivate failed', e)
    deactivateConfirmOpen.value = false
  } finally {
    deactivating.value = false
  }
}

async function openCustomerQr() {
  qrLoading.value = true
  try {
    qr.value = await api.getCustomerQr(providerId.value)
    qrModalOpen.value = true
  } catch (e) {
    console.error('[providers/[providerId]] getCustomerQr failed', e)
  } finally {
    qrLoading.value = false
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
      <header class="space-y-1">
        <NuxtLink
          :to="`/organizations/${orgId}/admin/point-cards`"
          class="text-sm text-primary-600 hover:underline dark:text-primary-400"
        >
          &larr; {{ t('wallet.admin.actions.back') }}
        </NuxtLink>
      </header>

      <div v-if="loading" class="py-6 text-center text-surface-500">
        {{ t('wallet.admin.providers.loading') }}
      </div>
      <div
        v-else-if="loadError"
        class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
      >
        {{ loadError }}
      </div>

      <template v-else-if="provider">
        <!-- 詳細表示 -->
        <div
          v-if="!editing"
          class="space-y-4 rounded-lg border border-surface-200 bg-white p-4 shadow-sm md:p-6 dark:border-surface-700 dark:bg-surface-900"
        >
          <div class="flex items-start gap-4">
            <div
              class="h-16 w-16 flex-shrink-0 rounded-lg"
              :style="{ backgroundColor: provider.brandColor ?? '#9ca3af' }"
              aria-hidden="true"
            />
            <div class="min-w-0 flex-1">
              <h1 class="text-xl font-bold">
                {{ provider.displayName }}
              </h1>
              <p class="mt-1 font-mono text-xs text-surface-500">
                {{ provider.code }}
              </p>
              <p class="mt-2 text-sm">
                <span
                  v-if="provider.isActive"
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
              </p>
            </div>
          </div>

          <dl class="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <dt class="text-xs font-medium text-surface-500">
                {{ t('wallet.admin.providers.brand_color') }}
              </dt>
              <dd class="mt-1 font-mono text-sm">
                {{ provider.brandColor ?? '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">
                {{ t('wallet.admin.providers.logo_url') }}
              </dt>
              <dd class="mt-1 truncate text-sm">
                {{ provider.logoUrl ?? '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">
                {{ t('wallet.admin.providers.card_number_length_hint') }}
              </dt>
              <dd class="mt-1 text-sm">
                {{ provider.cardNumberLengthHint ?? '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-xs font-medium text-surface-500">
                {{ t('wallet.admin.providers.type') }}
              </dt>
              <dd class="mt-1 font-mono text-sm">
                {{ provider.type }}
              </dd>
            </div>
          </dl>

          <!-- アクション -->
          <div class="flex flex-wrap gap-2 border-t border-surface-200 pt-4 dark:border-surface-700">
            <button
              type="button"
              class="rounded bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
              :disabled="qrLoading"
              @click="openCustomerQr"
            >
              {{ qrLoading ? t('wallet.admin.providers.qr_loading') : t('wallet.admin.providers.show_customer_qr') }}
            </button>
            <button
              v-if="isAdminOnly"
              type="button"
              class="rounded border border-surface-300 px-4 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
              @click="editing = true"
            >
              {{ t('wallet.admin.actions.edit') }}
            </button>
            <button
              v-if="isAdminOnly && provider.isActive"
              type="button"
              class="ml-auto rounded border border-red-400 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-700 dark:text-red-300 dark:hover:bg-red-950"
              @click="deactivateConfirmOpen = true"
            >
              {{ t('wallet.admin.providers.deactivate') }}
            </button>
          </div>
        </div>

        <!-- 編集モード -->
        <div
          v-else
          class="space-y-4 rounded-lg border border-surface-200 bg-white p-4 shadow-sm md:p-6 dark:border-surface-700 dark:bg-surface-900"
        >
          <h2 class="text-lg font-semibold">
            {{ t('wallet.admin.providers.edit') }}
          </h2>
          <div
            v-if="submitError"
            class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
            role="alert"
          >
            {{ submitError }}
          </div>
          <ProviderForm
            mode="edit"
            :initial="provider"
            :submitting="submitting"
            @submit="onSaveEdit"
            @cancel="editing = false"
          />
        </div>
      </template>
    </template>

    <!-- 停止確認モーダル -->
    <div
      v-if="deactivateConfirmOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      @click.self="deactivateConfirmOpen = false"
    >
      <div class="w-full max-w-md rounded-xl bg-white p-5 shadow-xl dark:bg-surface-900">
        <h2 class="text-lg font-semibold">
          {{ t('wallet.admin.providers.deactivate_title') }}
        </h2>
        <p class="mt-2 text-sm text-surface-600 dark:text-surface-400">
          {{ t('wallet.admin.providers.deactivate_confirm') }}
        </p>
        <div class="mt-4 flex justify-end gap-2">
          <button
            type="button"
            class="rounded border border-surface-300 px-4 py-2 text-sm hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
            :disabled="deactivating"
            @click="deactivateConfirmOpen = false"
          >
            {{ t('wallet.admin.actions.cancel') }}
          </button>
          <button
            type="button"
            class="rounded bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            :disabled="deactivating"
            @click="onDeactivate"
          >
            {{ deactivating ? t('wallet.admin.actions.processing') : t('wallet.admin.providers.deactivate') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 顧客 QR モーダル -->
    <CustomerQrModal
      v-model:visible="qrModalOpen"
      :qr="qr"
    />
  </div>
</template>
