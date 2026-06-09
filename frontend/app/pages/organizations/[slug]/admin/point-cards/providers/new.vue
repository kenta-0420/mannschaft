<script setup lang="ts">
/**
 * F18 Phase 2 — プロバイダー新規発行ページ。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §12.1
 *
 * <p>ADMIN のみ操作可能（DEPUTY_ADMIN は読み取りのみだがバックエンドが弾く設計）。
 * POINT_CARD_010（上限 20 個）エラー時は専用メッセージを表示する。
 */
import type { CreateOrgProviderRequest, UpdateOrgProviderRequest } from '~/types/orgPointCard'
import type { FetchError } from 'ofetch'

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
  myOrg.value?.role === 'ADMIN' || myOrg.value?.role === 'SYSTEM_ADMIN',
)

const submitting = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
})

async function onSubmit(body: CreateOrgProviderRequest | UpdateOrgProviderRequest) {
  // mode='create' のため ProviderForm からは必ず displayName 付き = CreateOrgProviderRequest 互換で来る。
  // 共通型を絞り込んで API を呼ぶ。
  if (!body.displayName) {
    error.value = t('wallet.admin.providers.validation.display_name_required')
    return
  }
  const createBody: CreateOrgProviderRequest = {
    displayName: body.displayName,
    brandColor: body.brandColor,
    logoUrl: body.logoUrl,
    cardNumberRegex: body.cardNumberRegex,
    cardNumberLengthHint: body.cardNumberLengthHint,
  }
  submitting.value = true
  error.value = null
  try {
    const created = await api.createProvider(createBody)
    await navigateTo(`/organizations/${orgSlug.value}/admin/point-cards/providers/${created.id}`)
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    if (code === 'POINT_CARD_010') {
      error.value = t('wallet.admin.providers.limit_exceeded')
    } else {
      error.value = fe.data?.message ?? t('wallet.admin.errors.save_failed')
    }
    console.error('[providers/new] createProvider failed', e)
  } finally {
    submitting.value = false
  }
}

function onCancel() {
  navigateTo(`/organizations/${orgSlug.value}/admin/point-cards`)
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <div v-if="!canAccess && myOrg" class="rounded-lg border border-amber-300 bg-amber-50 p-4 text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200">
      <p class="font-semibold">
        {{ t('wallet.admin.errors.access_denied_title') }}
      </p>
      <p class="mt-1 text-sm">
        {{ t('wallet.admin.errors.access_denied_admin_only') }}
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
          {{ t('wallet.admin.providers.new') }}
        </h1>
      </header>

      <div
        v-if="error"
        class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
        role="alert"
      >
        {{ error }}
      </div>

      <div class="max-w-2xl rounded-lg border border-surface-200 bg-white p-4 shadow-sm md:p-6 dark:border-surface-700 dark:bg-surface-900">
        <ProviderForm
          mode="create"
          :submitting="submitting"
          @submit="onSubmit"
          @cancel="onCancel"
        />
      </div>
    </template>
  </div>
</template>
