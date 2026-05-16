<script setup lang="ts">
/**
 * F18 Phase 2 — 店主スタンプ押印画面（最重要 UC-9）。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-9 / §8 UI/UX / §12.2
 *
 * <p>Phase 2 MVP の制約: バックエンドに「barcode_value → card_id を引く API」が無いため、
 * 押印フローは以下の MVP 設計とする:
 * <ol>
 *   <li>店主がプロバイダーを選ぶ</li>
 *   <li>カード ID（UUID）を直接入力（顧客側で `/wallet/cards/[id]` URL から取得）</li>
 *   <li>delta を選び（+1 / +2 / -1 / 任意）、メモを任意で入力</li>
 *   <li>押印を確定 → トースト + 直近 3 件履歴を表示</li>
 * </ol>
 * Phase 3 で「QR からカード ID を直接読み取る」フローを整備する旨を注意書きとして提示する。
 */
import type { OrgPointCardProvider, StampEventResponse } from '~/types/orgPointCard'
import type { FetchError } from 'ofetch'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const toast = useToast()
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

// ─── 状態 ────────────────────────────────────────────────────
const providers = ref<OrgPointCardProvider[]>([])
const providersLoading = ref(false)
const selectedProviderId = ref<string | null>(null)

// UUID 形式（簡易判定: 8-4-4-4-12）
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const cardIdInput = ref('')
const delta = ref<number>(1)
const memo = ref('')

const submitting = ref(false)
const submitError = ref<string | null>(null)

// 直近押印履歴
const recentStamps = ref<StampEventResponse[]>([])

async function fetchProviders() {
  providersLoading.value = true
  try {
    providers.value = await api.listProviders(true)
    // 最初の SELF_ISSUED_STAMP を初期選択
    const first = providers.value.find(p => p.type === 'SELF_ISSUED_STAMP')
    if (first) selectedProviderId.value = first.id
  } catch (e) {
    console.error('[stamp] listProviders failed', e)
  } finally {
    providersLoading.value = false
  }
}

async function fetchRecent() {
  if (!selectedProviderId.value) {
    recentStamps.value = []
    return
  }
  try {
    const page = await api.listOrgStamps({
      providerId: selectedProviderId.value,
      page: 0,
      size: 3,
    })
    recentStamps.value = page.content
  } catch (e) {
    console.error('[stamp] listOrgStamps failed', e)
    recentStamps.value = []
  }
}

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  if (canAccess.value) {
    await fetchProviders()
    await fetchRecent()
  }
})

watch(selectedProviderId, () => {
  void fetchRecent()
})

// ─── バリデーション ──────────────────────────────────────────
const cardIdError = computed(() => {
  if (!cardIdInput.value.trim()) return null
  if (!UUID_PATTERN.test(cardIdInput.value.trim())) {
    return t('wallet.admin.stamp.card_id_invalid')
  }
  return null
})

const canSubmit = computed(() =>
  !submitting.value
  && !!selectedProviderId.value
  && UUID_PATTERN.test(cardIdInput.value.trim())
  && delta.value !== 0
  && delta.value >= -100
  && delta.value <= 100,
)

// ─── 押印 ────────────────────────────────────────────────────
async function pressStamp() {
  if (!canSubmit.value) return
  submitting.value = true
  submitError.value = null
  try {
    const event = await api.stamp(cardIdInput.value.trim(), {
      delta: delta.value,
      memo: memo.value.trim() || undefined,
    })
    toast.add({
      severity: 'success',
      summary: t('wallet.admin.stamp.press_success', { count: event.delta }),
      life: 3000,
    })
    // フォームリセット（プロバイダー選択は残す）
    cardIdInput.value = ''
    delta.value = 1
    memo.value = ''
    await fetchRecent()
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    const msg = errorCodeMessage(code) ?? fe.data?.message ?? t('wallet.admin.errors.save_failed')
    submitError.value = msg
    toast.add({ severity: 'error', summary: msg, life: 5000 })
    console.error('[stamp] press failed', e)
  } finally {
    submitting.value = false
  }
}

function errorCodeMessage(code: string | undefined): string | null {
  switch (code) {
    case 'POINT_CARD_006': return t('wallet.admin.stamp.error_card_not_found')
    case 'POINT_CARD_011': return t('wallet.admin.stamp.error_not_owned')
    case 'POINT_CARD_012': return t('wallet.admin.stamp.error_invalid_provider')
    case 'POINT_CARD_013': return t('wallet.admin.stamp.error_invalid_provider_type')
    case 'POINT_CARD_014': return t('wallet.admin.stamp.error_delta_zero')
    case 'POINT_CARD_008': return t('wallet.admin.stamp.error_rate_limit')
    default: return null
  }
}

function quickDelta(v: number) {
  delta.value = v
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
          {{ t('wallet.admin.stamp.title') }}
        </h1>
      </header>

      <!-- Phase 3 注意書き -->
      <div class="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200">
        <p class="font-medium">
          {{ t('wallet.admin.stamp.phase3_note_title') }}
        </p>
        <p class="mt-1">
          {{ t('wallet.admin.stamp.phase3_note') }}
        </p>
      </div>

      <div class="grid gap-4 lg:grid-cols-2">
        <!-- 左: 押印フォーム -->
        <section class="space-y-4 rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900">
          <!-- プロバイダー選択 -->
          <div>
            <label for="stamp-provider" class="mb-1 block text-sm font-medium">
              {{ t('wallet.admin.stamp.select_provider') }}
              <span class="text-red-500">*</span>
            </label>
            <select
              id="stamp-provider"
              v-model="selectedProviderId"
              class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
              :disabled="providersLoading"
            >
              <option v-if="providersLoading" value="">
                {{ t('wallet.admin.providers.loading') }}
              </option>
              <option v-else-if="providers.length === 0" value="">
                {{ t('wallet.admin.providers.empty') }}
              </option>
              <option
                v-for="p in providers"
                :key="p.id"
                :value="p.id"
                :disabled="p.type !== 'SELF_ISSUED_STAMP'"
              >
                {{ p.displayName }} ({{ p.code }})
              </option>
            </select>
          </div>

          <!-- カード ID 入力 -->
          <div>
            <label for="stamp-card-id" class="mb-1 block text-sm font-medium">
              {{ t('wallet.admin.stamp.card_id_input') }}
              <span class="text-red-500">*</span>
            </label>
            <input
              id="stamp-card-id"
              v-model="cardIdInput"
              type="text"
              class="w-full rounded border border-surface-300 px-3 py-2 font-mono text-sm dark:border-surface-600 dark:bg-surface-800"
              placeholder="00000000-0000-0000-0000-000000000000"
              :aria-invalid="!!cardIdError"
              autocomplete="off"
            >
            <p class="mt-1 text-xs text-surface-500">
              {{ t('wallet.admin.stamp.card_id_hint') }}
            </p>
            <p v-if="cardIdError" class="mt-1 text-xs text-red-600" role="alert">
              {{ cardIdError }}
            </p>
          </div>

          <!-- カメラ占有 UI（Phase 3 で本格対応） -->
          <details class="rounded border border-dashed border-surface-300 p-3 dark:border-surface-600">
            <summary class="cursor-pointer text-sm font-medium">
              {{ t('wallet.admin.stamp.scanner_unavailable_title') }}
            </summary>
            <p class="mt-2 text-xs text-surface-600 dark:text-surface-400">
              {{ t('wallet.admin.stamp.scanner_unavailable_body') }}
            </p>
          </details>

          <!-- delta -->
          <div>
            <label for="stamp-delta" class="mb-1 block text-sm font-medium">
              {{ t('wallet.admin.stamp.delta_label') }}
            </label>
            <div class="flex flex-wrap items-center gap-2">
              <button
                type="button"
                class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': delta === 1 }"
                @click="quickDelta(1)"
              >
                {{ t('wallet.admin.stamp.delta_plus_one') }}
              </button>
              <button
                type="button"
                class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': delta === 2 }"
                @click="quickDelta(2)"
              >
                +2
              </button>
              <button
                type="button"
                class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': delta === -1 }"
                @click="quickDelta(-1)"
              >
                -1
              </button>
              <input
                id="stamp-delta"
                v-model.number="delta"
                type="number"
                class="w-24 rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
                min="-100"
                max="100"
              >
            </div>
            <p v-if="delta === 0" class="mt-1 text-xs text-red-600" role="alert">
              {{ t('wallet.admin.stamp.error_delta_zero') }}
            </p>
          </div>

          <!-- メモ -->
          <div>
            <label for="stamp-memo" class="mb-1 block text-sm font-medium">
              {{ t('wallet.admin.stamp.memo') }}
            </label>
            <input
              id="stamp-memo"
              v-model="memo"
              type="text"
              class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
              maxlength="200"
              :placeholder="t('wallet.admin.stamp.memo_placeholder')"
            >
          </div>

          <!-- エラー表示 -->
          <div
            v-if="submitError"
            class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
            role="alert"
          >
            {{ submitError }}
          </div>

          <!-- 押印ボタン -->
          <button
            type="button"
            class="w-full rounded bg-primary-600 px-4 py-3 text-base font-semibold text-white hover:bg-primary-700 disabled:opacity-50"
            :disabled="!canSubmit"
            @click="pressStamp"
          >
            {{ submitting ? t('wallet.admin.stamp.pressing') : t('wallet.admin.stamp.press') }}
          </button>
        </section>

        <!-- 右: 直近 3 件履歴 -->
        <section class="space-y-3 rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900">
          <div class="flex items-center justify-between">
            <h2 class="text-sm font-semibold">
              {{ t('wallet.admin.stamp.recent') }}
            </h2>
            <NuxtLink
              :to="`/organizations/${orgId}/admin/point-cards/history`"
              class="text-xs text-primary-600 hover:underline dark:text-primary-400"
            >
              {{ t('wallet.admin.stamp.view_all') }} &rarr;
            </NuxtLink>
          </div>
          <StampHistoryTable :stamps="recentStamps" />
        </section>
      </div>
    </template>
  </div>
</template>
