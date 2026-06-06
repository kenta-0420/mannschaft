<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 店主スタンプ・残高操作画面（UC-9 拡張）。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-9 / §8 UI/UX / §12.1 / §16
 *
 * <p>Phase 3 ではカード特定方法を 2 モード化し、SELF_ISSUED_BALANCE 型に対応する:
 * <ol>
 *   <li>QR 読取モード — 顧客の一時トークン QR をカメラで読み、{@code resolveByToken} で
 *       cardId を特定する。GETDEL で 1 回限り消費される（再生防止）。</li>
 *   <li>カード ID 直接入力モード — UUID をテキスト入力（Phase 2 の MVP 動線を残す）。</li>
 * </ol>
 *
 * <p>カード解決後、{@code providerType} に応じて操作タブを切替える:
 * <ul>
 *   <li>SELF_ISSUED_STAMP — 押印タブ（既存 +1/+2/-1）</li>
 *   <li>SELF_ISSUED_BALANCE — チャージ / 利用 / 返金の 3 タブ</li>
 *   <li>EXTERNAL — 店主側操作は不可（カード追加用 QR とは異なる経路）</li>
 * </ul>
 */
import type { FetchError } from 'ofetch'
import type { BalanceEventResponse, OrgPointCardProvider, ResolveTokenResponse, StampEventResponse } from '~/types/orgPointCard'
import type { BarcodeFormat } from '~/types/pointCard'
import { useToast } from 'primevue/usetoast'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const toast = useToast()
const orgStore = useOrganizationStore()
const orgId = computed(() => String(route.params.id))
const runtimeConfig = useRuntimeConfig()

const api = useOrgWalletApi(() => orgId.value)

// F18 SELF_ISSUED_BALANCE 凍結（2026-05-17 マスター御裁可）
// 資金決済法対応のため法務整備が整うまで残高 3 タブを非表示。
// 設計書: docs/features/F18_point_card_wallet.md §1.4 / §16 / §17
const balanceEnabled = computed<boolean>(() => Boolean(runtimeConfig.public.f18BalanceEnabled))

const myOrg = computed(() =>
  orgStore.myOrganizations.find(o => String(o.id) === orgId.value),
)
const canAccess = computed(() =>
  myOrg.value?.role === 'ADMIN'
  || myOrg.value?.role === 'SYSTEM_ADMIN'
  || myOrg.value?.role === 'DEPUTY_ADMIN',
)

// UUID 形式（簡易判定: 8-4-4-4-12）
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

// ─── カード特定モード ────────────────────────────────────────
type ResolveMode = 'qr' | 'manual'
const resolveMode = ref<ResolveMode>('qr')

const cardIdInput = ref('')
const manualError = ref<string | null>(null)

const resolvedCard = ref<ResolveTokenResponse | null>(null)
const resolving = ref(false)

// ─── 操作タブ（カード解決後に切替） ──────────────────────────
type OpTab = 'stamp' | 'charge' | 'spent' | 'refund'
const opTab = ref<OpTab>('stamp')

// プロバイダー（手動入力モードで参照する場合のみ使用）
const providers = ref<OrgPointCardProvider[]>([])

// ─── スタンプタブ（既存ロジック） ───────────────────────────
const stampDelta = ref<number>(1)
const stampMemo = ref('')
const stampSubmitting = ref(false)
const stampError = ref<string | null>(null)
const recentStamps = ref<StampEventResponse[]>([])

// ─── 初期化 ──────────────────────────────────────────────────
async function fetchProviders() {
  try {
    providers.value = await api.listProviders(true)
  } catch (e) {
    console.error('[stamp] listProviders failed', e)
  }
}

onMounted(async () => {
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
  if (canAccess.value) {
    await fetchProviders()
  }
})

// ─── QR 読取モード ───────────────────────────────────────────
/**
 * BarcodeCapture から受け取った値からトークンを抽出して resolve する。
 * 顧客側 share QR の形式:
 *   - mannschaft://wallet/share?token=UUID
 *   - https://<host>/wallet/share?token=UUID（PWA フォールバック）
 * 純粋な UUID 文字列が来た場合は token そのものとして扱う。
 */
async function onQrDetected(value: string, _format: BarcodeFormat) {
  const token = extractToken(value)
  if (!token) {
    toast.add({
      severity: 'error',
      summary: t('wallet.admin.stamp.token_invalid'),
      life: 5000,
    })
    return
  }
  await resolveToken(token)
}

function extractToken(qrValue: string): string | null {
  const raw = qrValue.trim()
  if (!raw) return null

  // 純 UUID 形式
  if (UUID_PATTERN.test(raw)) return raw

  // URL 系（mannschaft:// は WHATWG URL がパースできないため https:// に置換）
  try {
    const normalized = raw.startsWith('mannschaft://')
      ? raw.replace('mannschaft://', 'https://app.local/')
      : raw
    const url = new URL(normalized)
    const token = url.searchParams.get('token')
    if (token && UUID_PATTERN.test(token)) return token
  } catch {
    // パース失敗 → 不正な QR
  }
  return null
}

async function resolveToken(token: string) {
  resolving.value = true
  try {
    const res = await api.resolveByToken(token)
    resolvedCard.value = res
    // 操作タブの初期値
    // F18 BALANCE 凍結中（f18BalanceEnabled=false）のときは SELF_ISSUED_BALANCE でも
    // 操作タブを開かない（バナーのみ表示）。stamp を初期値にしておくと SELF_ISSUED_STAMP
    // 判定 v-if と合わせて該当タブが何も表示されない自然な状態になる。
    opTab.value
      = res.providerType === 'SELF_ISSUED_BALANCE' && balanceEnabled.value
        ? 'charge'
        : 'stamp'
    await fetchRecentStamps()
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    const msg = code === 'POINT_CARD_019'
      ? t('wallet.admin.stamp.token_invalid')
      : fe.data?.message ?? t('wallet.admin.stamp.card_not_found')
    toast.add({ severity: 'error', summary: msg, life: 5000 })
    console.error('[stamp] resolveByToken failed', e)
  } finally {
    resolving.value = false
  }
}

// ─── 手動カード ID 入力モード ──────────────────────────────
/**
 * 手動入力時はトークン経由ではなく直接 cardId をフォームに使う。
 * resolve API を経由しないため providerType が分からない → カード一覧から
 * 推測することはできない。Phase 2 互換動線として STAMP 用前提とする。
 *
 * バックエンドのスタンプ API は対象カードの provider 整合性を検証するため、
 * 不適切な型のカードに対しては POINT_CARD_013 で根治される。
 * 残高操作は cardId 直入力モードでは行わない（resolve 経由のみとする）。
 */
function applyManualCardId() {
  manualError.value = null
  const v = cardIdInput.value.trim()
  if (!UUID_PATTERN.test(v)) {
    manualError.value = t('wallet.admin.stamp.card_id_invalid')
    return
  }
  // 手動入力では type 不明のため STAMP 型として擬似的に解決する。
  // 残高操作タブは表示しない（resolvedCard.providerType を STAMP として扱う）。
  resolvedCard.value = {
    cardId: v,
    providerId: '',
    providerDisplayName: t('wallet.admin.stamp.manual_resolved_label'),
    providerType: 'SELF_ISSUED_STAMP',
    last4: null,
    currentStampCount: null,
    currentBalance: null,
  }
  opTab.value = 'stamp'
  void fetchRecentStamps()
}

function clearResolved() {
  resolvedCard.value = null
  cardIdInput.value = ''
  manualError.value = null
  recentStamps.value = []
  stampDelta.value = 1
  stampMemo.value = ''
  stampError.value = null
}

// ─── 押印（既存ロジックを cardId 解決後に呼ぶ形へ） ──────────
const canSubmitStamp = computed(() =>
  !stampSubmitting.value
  && !!resolvedCard.value
  && stampDelta.value !== 0
  && stampDelta.value >= -100
  && stampDelta.value <= 100,
)

function quickDelta(v: number) {
  stampDelta.value = v
}

async function fetchRecentStamps() {
  if (!resolvedCard.value) {
    recentStamps.value = []
    return
  }
  try {
    const list = await api.listCardStamps(resolvedCard.value.cardId)
    recentStamps.value = list.slice(0, 3)
  } catch (e) {
    console.error('[stamp] listCardStamps failed', e)
    recentStamps.value = []
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

async function pressStamp() {
  if (!canSubmitStamp.value || !resolvedCard.value) return
  stampSubmitting.value = true
  stampError.value = null
  try {
    const event = await api.stamp(resolvedCard.value.cardId, {
      delta: stampDelta.value,
      memo: stampMemo.value.trim() || undefined,
    })
    toast.add({
      severity: 'success',
      summary: t('wallet.admin.stamp.press_success', { count: event.delta }),
      life: 3000,
    })
    stampDelta.value = 1
    stampMemo.value = ''
    // resolve 経由なら currentStampCount を更新（QR モード用）
    if (resolvedCard.value.currentStampCount !== null) {
      resolvedCard.value = {
        ...resolvedCard.value,
        currentStampCount: (resolvedCard.value.currentStampCount ?? 0) + event.delta,
      }
    }
    await fetchRecentStamps()
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    const msg = errorCodeMessage(code) ?? fe.data?.message ?? t('wallet.admin.errors.save_failed')
    stampError.value = msg
    toast.add({ severity: 'error', summary: msg, life: 5000 })
    console.error('[stamp] press failed', e)
  } finally {
    stampSubmitting.value = false
  }
}

// ─── 残高操作完了時のカード状態再計算 ───────────────────────
function onBalanceDone(event: BalanceEventResponse) {
  if (!resolvedCard.value) return
  // balanceAfter で resolvedCard を更新（再 resolve しない = トークンは消費済のため）
  resolvedCard.value = {
    ...resolvedCard.value,
    currentBalance: event.balanceAfter,
  }
}

const currentBalanceNum = computed(() => {
  if (!resolvedCard.value?.currentBalance) return 0
  const n = parseFloat(resolvedCard.value.currentBalance)
  return Number.isNaN(n) ? 0 : n
})
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

      <!-- 1. カード特定セクション -->
      <section
        v-if="!resolvedCard"
        class="space-y-3 rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900"
      >
        <h2 class="text-base font-semibold">
          {{ t('wallet.admin.stamp.resolve_title') }}
        </h2>

        <!-- モード切替 -->
        <div class="flex gap-2" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="resolveMode === 'qr'"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm dark:border-surface-600"
            :class="{ 'bg-primary-600 text-white border-primary-600': resolveMode === 'qr' }"
            @click="resolveMode = 'qr'"
          >
            {{ t('wallet.admin.stamp.resolve_qr') }}
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="resolveMode === 'manual'"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm dark:border-surface-600"
            :class="{ 'bg-primary-600 text-white border-primary-600': resolveMode === 'manual' }"
            @click="resolveMode = 'manual'"
          >
            {{ t('wallet.admin.stamp.resolve_manual') }}
          </button>
        </div>

        <!-- QR モード -->
        <div v-if="resolveMode === 'qr'">
          <p class="mb-2 text-xs text-surface-600 dark:text-surface-400">
            {{ t('wallet.admin.stamp.resolve_qr_hint') }}
          </p>
          <BarcodeCapture @detected="onQrDetected" />
          <p v-if="resolving" class="mt-2 text-sm text-surface-600">
            {{ t('wallet.admin.stamp.resolving') }}
          </p>
        </div>

        <!-- 手動モード -->
        <div v-else class="space-y-2">
          <label for="manual-card-id" class="block text-sm font-medium">
            {{ t('wallet.admin.stamp.card_id_input') }}
            <span class="text-red-500">*</span>
          </label>
          <input
            id="manual-card-id"
            v-model="cardIdInput"
            type="text"
            class="w-full rounded border border-surface-300 px-3 py-2 font-mono text-sm dark:border-surface-600 dark:bg-surface-800"
            placeholder="00000000-0000-0000-0000-000000000000"
            autocomplete="off"
            @keyup.enter="applyManualCardId"
          >
          <p class="text-xs text-surface-500">
            {{ t('wallet.admin.stamp.card_id_hint') }}
          </p>
          <p v-if="manualError" class="text-xs text-red-600" role="alert">
            {{ manualError }}
          </p>
          <button
            type="button"
            class="rounded bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
            @click="applyManualCardId"
          >
            {{ t('wallet.admin.stamp.manual_apply') }}
          </button>
        </div>
      </section>

      <!-- 2. カード解決済み表示 + 操作タブ -->
      <section
        v-if="resolvedCard"
        class="space-y-4 rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900"
      >
        <!-- 解決済みカード情報 -->
        <div class="flex flex-wrap items-start justify-between gap-2">
          <div class="space-y-1">
            <div class="text-sm text-surface-600 dark:text-surface-400">
              {{ t('wallet.admin.stamp.resolved_label') }}
            </div>
            <div class="text-lg font-semibold">
              {{ resolvedCard.providerDisplayName }}
            </div>
            <div v-if="resolvedCard.last4" class="text-sm font-mono">
              {{ t('wallet.admin.stamp.last4_label') }}: ●●●● {{ resolvedCard.last4 }}
            </div>
            <div
              v-if="resolvedCard.providerType === 'SELF_ISSUED_STAMP' && resolvedCard.currentStampCount !== null"
              class="text-sm"
            >
              {{ t('wallet.admin.stamp.current_stamp') }}:
              <span class="font-mono font-semibold">{{ resolvedCard.currentStampCount }}</span>
            </div>
            <div
              v-if="resolvedCard.providerType === 'SELF_ISSUED_BALANCE'"
              class="text-sm"
            >
              {{ t('wallet.admin.balance.current_balance') }}:
              <span class="font-mono font-semibold">
                <template v-if="balanceEnabled">¥{{ currentBalanceNum.toLocaleString() }}</template>
                <template v-else>---</template>
              </span>
            </div>
            <!-- F18 BALANCE 凍結バナー（資金決済法対応のため一時停止中） -->
            <div
              v-if="resolvedCard.providerType === 'SELF_ISSUED_BALANCE' && !balanceEnabled"
              class="mt-2 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
              role="status"
            >
              <p class="font-semibold">
                {{ t('wallet.balance.disabled.banner') }}
              </p>
              <p>{{ t('wallet.balance.disabled.reason') }}</p>
            </div>
          </div>
          <button
            type="button"
            class="rounded border border-surface-300 px-3 py-1.5 text-sm hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
            @click="clearResolved"
          >
            {{ t('wallet.admin.stamp.resolve_again') }}
          </button>
        </div>

        <!-- EXTERNAL は店主側操作不可 -->
        <div
          v-if="resolvedCard.providerType === 'EXTERNAL'"
          class="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
        >
          {{ t('wallet.admin.stamp.external_unsupported') }}
        </div>

        <!-- 操作タブ -->
        <div v-else>
          <div class="mb-3 flex flex-wrap gap-2 border-b border-surface-200 pb-2 dark:border-surface-700" role="tablist">
            <button
              v-if="resolvedCard.providerType === 'SELF_ISSUED_STAMP'"
              type="button"
              role="tab"
              :aria-selected="opTab === 'stamp'"
              class="rounded px-3 py-1.5 text-sm font-medium"
              :class="opTab === 'stamp' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
              @click="opTab = 'stamp'"
            >
              {{ t('wallet.admin.stamp.tab_stamp') }}
            </button>
            <!-- F18 BALANCE 凍結中（balanceEnabled=false）はタブそのものを描画しない -->
            <template v-if="resolvedCard.providerType === 'SELF_ISSUED_BALANCE' && balanceEnabled">
              <button
                type="button"
                role="tab"
                :aria-selected="opTab === 'charge'"
                class="rounded px-3 py-1.5 text-sm font-medium"
                :class="opTab === 'charge' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
                @click="opTab = 'charge'"
              >
                {{ t('wallet.admin.balance.tab_charge') }}
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="opTab === 'spent'"
                class="rounded px-3 py-1.5 text-sm font-medium"
                :class="opTab === 'spent' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
                @click="opTab = 'spent'"
              >
                {{ t('wallet.admin.balance.tab_spent') }}
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="opTab === 'refund'"
                class="rounded px-3 py-1.5 text-sm font-medium"
                :class="opTab === 'refund' ? 'bg-primary-600 text-white' : 'border border-surface-300 dark:border-surface-600'"
                @click="opTab = 'refund'"
              >
                {{ t('wallet.admin.balance.tab_refund') }}
              </button>
            </template>
          </div>

          <!-- スタンプ押印タブ -->
          <div v-if="opTab === 'stamp'" class="space-y-4">
            <div>
              <label for="stamp-delta" class="mb-1 block text-sm font-medium">
                {{ t('wallet.admin.stamp.delta_label') }}
              </label>
              <div class="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                  :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': stampDelta === 1 }"
                  @click="quickDelta(1)"
                >
                  {{ t('wallet.admin.stamp.delta_plus_one') }}
                </button>
                <button
                  type="button"
                  class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                  :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': stampDelta === 2 }"
                  @click="quickDelta(2)"
                >
                  +2
                </button>
                <button
                  type="button"
                  class="rounded border border-surface-300 px-3 py-2 text-sm font-medium hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                  :class="{ 'bg-primary-600 text-white border-primary-600 hover:bg-primary-700': stampDelta === -1 }"
                  @click="quickDelta(-1)"
                >
                  -1
                </button>
                <input
                  id="stamp-delta"
                  v-model.number="stampDelta"
                  type="number"
                  class="w-24 rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
                  min="-100"
                  max="100"
                >
              </div>
              <p v-if="stampDelta === 0" class="mt-1 text-xs text-red-600" role="alert">
                {{ t('wallet.admin.stamp.error_delta_zero') }}
              </p>
            </div>

            <div>
              <label for="stamp-memo" class="mb-1 block text-sm font-medium">
                {{ t('wallet.admin.stamp.memo') }}
              </label>
              <input
                id="stamp-memo"
                v-model="stampMemo"
                type="text"
                class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
                maxlength="200"
                :placeholder="t('wallet.admin.stamp.memo_placeholder')"
              >
            </div>

            <div
              v-if="stampError"
              class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
              role="alert"
            >
              {{ stampError }}
            </div>

            <button
              type="button"
              class="w-full rounded bg-primary-600 px-4 py-3 text-base font-semibold text-white hover:bg-primary-700 disabled:opacity-50"
              :disabled="!canSubmitStamp"
              @click="pressStamp"
            >
              {{ stampSubmitting ? t('wallet.admin.stamp.pressing') : t('wallet.admin.stamp.press') }}
            </button>

            <!-- 直近押印履歴 -->
            <div v-if="recentStamps.length > 0" class="mt-4 border-t border-surface-200 pt-3 dark:border-surface-700">
              <h3 class="mb-2 text-sm font-semibold">
                {{ t('wallet.admin.stamp.recent') }}
              </h3>
              <StampHistoryTable :stamps="recentStamps" />
            </div>
          </div>

          <!-- チャージ（F18 BALANCE 凍結中は描画しない） -->
          <BalanceChargeTabPanel
            v-else-if="opTab === 'charge' && resolvedCard.providerType === 'SELF_ISSUED_BALANCE' && balanceEnabled"
            :card-id="resolvedCard.cardId"
            :org-id="orgId"
            @done="onBalanceDone"
          />

          <!-- 利用（F18 BALANCE 凍結中は描画しない） -->
          <BalanceSpendTabPanel
            v-else-if="opTab === 'spent' && resolvedCard.providerType === 'SELF_ISSUED_BALANCE' && balanceEnabled"
            :card-id="resolvedCard.cardId"
            :org-id="orgId"
            :current-balance="currentBalanceNum"
            @done="onBalanceDone"
          />

          <!-- 返金（F18 BALANCE 凍結中は描画しない） -->
          <BalanceRefundTabPanel
            v-else-if="opTab === 'refund' && resolvedCard.providerType === 'SELF_ISSUED_BALANCE' && balanceEnabled"
            :card-id="resolvedCard.cardId"
            :org-id="orgId"
            @done="onBalanceDone"
          />
        </div>
      </section>
    </template>
  </div>
</template>
