<script setup lang="ts">
import Select from 'primevue/select'
import Button from 'primevue/button'
import Paginator from 'primevue/paginator'
import Tag from 'primevue/tag'
import type {
  ScopeKind,
  EscrowStatus,
  ReceivedEscrowResponse,
} from '~/types/marketPayment'

/**
 * F22.1 謝礼決済 フォロー Wave B: 受取側 ADMIN の謝礼受取／返金管理画面。
 *
 * 受取側（応じ手＝payee 本人 or そのチーム/組織 ADMIN）が、受け取った謝礼エスクローを一覧し、
 * 返金（{@link MarketRefundDialog}）を行うための画面。
 *
 * BE 配線（recon 済み・実在 EP・casing は BE camelCase と 1:1）:
 *   - GET  /api/v1/payment/escrow/received?scopeKind&scopeId&status&page&size  （Wave A #1452・受取側一覧）
 *   - POST /api/v1/payment/escrow/{id}/refund                                  （返金・受取側 ADMIN）
 *
 * scope 選択:
 *   - USER（本人）: 認証ユーザー自身（authStore の id）。
 *   - TEAM/ORG: 自分が ADMIN のチーム/組織（teamStore.adminTeams / orgStore.adminOrganizations）。
 *   実際の認可（受取 scope の本人/ADMIN）と IDOR は BE 側で担保する（無関係 scope は 403）。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const marketPaymentApi = useMarketPaymentApi()
const { formatDate } = useDatetime()
const authStore = useAuthStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

/** scope 選択肢 1 件（USER 本人 / 自分が ADMIN の TEAM・ORG）。 */
interface ScopeOption {
  /** 一意キー（kind:id）。 */
  key: string
  kind: ScopeKind
  /** scopeId（USER は本人 id・TEAM は teams.id・ORG は organizations.id）。 */
  id: number
  /** 表示ラベル。 */
  label: string
}

/** 返金可能な状態（CAPTURED / PARTIALLY_REFUNDED のみ）。 */
const REFUNDABLE_STATUSES: ReadonlySet<EscrowStatus> = new Set<EscrowStatus>([
  'CAPTURED',
  'PARTIALLY_REFUNDED',
])

/** status フィルタ候補（全 9 値・先頭は「すべて」）。 */
const ALL_STATUSES: EscrowStatus[] = [
  'PENDING_CONFIRMATION',
  'DEFERRED',
  'AUTHORIZED',
  'HELD',
  'CAPTURED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'CANCELLED',
  'DISPUTED',
]

const scopeOptions = computed<ScopeOption[]>(() => {
  const opts: ScopeOption[] = []
  const me = authStore.currentUser
  if (me) {
    opts.push({ key: `USER:${me.id}`, kind: 'USER', id: me.id, label: t('market.payment.received.scope.self') })
  }
  for (const team of teamStore.adminTeams) {
    opts.push({
      key: `TEAM:${team.id}`,
      kind: 'TEAM',
      id: team.id,
      label: `${t('market.payment.received.scope.team')}: ${team.name}`,
    })
  }
  for (const org of orgStore.adminOrganizations) {
    opts.push({
      key: `ORG:${org.id}`,
      kind: 'ORG',
      id: org.id,
      label: `${t('market.payment.received.scope.org')}: ${org.name}`,
    })
  }
  return opts
})

const selectedScope = ref<ScopeOption | null>(null)

interface StatusOption {
  value: EscrowStatus | null
  label: string
}
const statusOptions = computed<StatusOption[]>(() => [
  { value: null, label: t('market.payment.received.statusFilterAll') },
  ...ALL_STATUSES.map((s) => ({ value: s, label: t(`market.payment.received.status.${s}`) })),
])
const selectedStatus = ref<EscrowStatus | null>(null)

const items = ref<ReceivedEscrowResponse[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const loading = ref(false)
const errorMessage = ref<string | null>(null)

/** 返金ダイアログ状態。 */
const refundDialogVisible = ref(false)
const refundTargetEscrowId = ref<string>('')
const refundTargetMaxAmount = ref<number | null>(null)

async function load() {
  const scope = selectedScope.value
  if (!scope) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  errorMessage.value = null
  try {
    const res = await marketPaymentApi.getReceivedEscrows(scope.kind, scope.id, {
      status: selectedStatus.value,
      page: page.value,
      size: pageSize.value,
    })
    items.value = res.data
    total.value = res.meta.total
  } catch (e: unknown) {
    items.value = []
    total.value = 0
    errorMessage.value = e instanceof Error ? e.message : t('market.payment.received.loadFailed')
  } finally {
    loading.value = false
  }
}

/** scope/status 変更時は 0 ページから取得し直す。 */
function reloadFromFirstPage() {
  page.value = 0
  void load()
}

watch(selectedScope, reloadFromFirstPage)
watch(selectedStatus, reloadFromFirstPage)

function onPageChange(event: { page: number }) {
  page.value = event.page
  void load()
}

/** 行が返金可能か（CAPTURED / PARTIALLY_REFUNDED）。 */
function isRefundable(item: ReceivedEscrowResponse): boolean {
  return REFUNDABLE_STATUSES.has(item.status)
}

/** 行の正味受取額（chargeAmount − applicationFeeAmount − refundedAmount）を一部返金の上限に使う。 */
function refundableRemaining(item: ReceivedEscrowResponse): number {
  return Math.max(0, item.chargeAmount - item.applicationFeeAmount - item.refundedAmount)
}

function openRefund(item: ReceivedEscrowResponse) {
  refundTargetEscrowId.value = item.escrowTransactionId
  refundTargetMaxAmount.value = refundableRemaining(item)
  refundDialogVisible.value = true
}

function onRefunded() {
  // 返金成功後は一覧を再取得して返金済額/状態を反映する。
  void load()
}

/** 円整数を通貨フォーマットで返す（最小通貨単位＝円）。 */
function formatYen(amount: number): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'JPY' }).format(amount)
}

/** status に応じた Tag severity。 */
function statusSeverity(status: EscrowStatus): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'CAPTURED':
      return 'success'
    case 'PARTIALLY_REFUNDED':
      return 'warn'
    case 'REFUNDED':
    case 'CANCELLED':
    case 'DISPUTED':
      return 'danger'
    case 'AUTHORIZED':
    case 'HELD':
      return 'info'
    default:
      return 'secondary'
  }
}

onMounted(async () => {
  // 自分が ADMIN のチーム/組織を取得して scope 候補を構築する。
  await Promise.all([
    teamStore.myTeams.length ? Promise.resolve() : teamStore.fetchMyTeams(),
    orgStore.myOrganizations.length ? Promise.resolve() : orgStore.fetchMyOrganizations(),
  ])
  // 既定は本人 scope（先頭）。
  if (scopeOptions.value.length > 0) {
    selectedScope.value = scopeOptions.value[0] ?? null
  }
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('market.payment.received.pageTitle')" class="mb-2" />
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('market.payment.received.pageSubtitle') }}
    </p>

    <!-- scope/status フィルタ -->
    <div class="mb-4 flex flex-wrap items-end gap-4">
      <div class="flex flex-col gap-1">
        <label class="text-xs font-medium text-surface-600" for="scope-select">
          {{ $t('market.payment.received.scopeLabel') }}
        </label>
        <Select
          v-if="scopeOptions.length > 0"
          v-model="selectedScope"
          input-id="scope-select"
          :options="scopeOptions"
          option-label="label"
          data-key="key"
          class="min-w-[16rem]"
          data-testid="received-scope-select"
        />
        <p v-else class="text-sm text-surface-400">
          {{ $t('market.payment.received.noScope') }}
        </p>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-xs font-medium text-surface-600" for="status-select">
          {{ $t('market.payment.received.statusFilterLabel') }}
        </label>
        <Select
          v-model="selectedStatus"
          input-id="status-select"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          class="min-w-[12rem]"
          data-testid="received-status-select"
        />
      </div>
    </div>

    <!-- 受取口座（Connect onboarding）状態・登録導線（選択中 scope の受取設定）。 -->
    <MarketConnectOnboarding
      v-if="selectedScope"
      :key="selectedScope.key"
      class="mb-4"
      data-testid="received-connect-onboarding"
      :scope-kind="selectedScope.kind"
      :scope-id="selectedScope.id"
    />

    <div v-if="loading" class="flex justify-center p-8">
      <LoadingBounce />
    </div>

    <template v-else>
      <p v-if="errorMessage" class="mb-3 text-sm text-red-600" role="alert">
        {{ errorMessage }}
      </p>

      <div
        v-if="items.length === 0 && scopeOptions.length > 0 && !errorMessage"
        class="rounded border border-dashed p-8 text-center text-surface-400"
        data-testid="received-empty"
      >
        {{ $t('market.payment.received.empty') }}
      </div>

      <div v-else class="flex flex-col gap-3" data-testid="received-list">
        <div
          v-for="item in items"
          :key="item.escrowTransactionId"
          class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
          data-testid="received-row"
        >
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div class="flex-1">
              <div class="flex items-center gap-2">
                <span class="font-semibold">
                  {{ $t(`market.payment.received.sourceKind.${item.sourceKind}`) }}
                </span>
                <Tag
                  :value="$t(`market.payment.received.status.${item.status}`)"
                  :severity="statusSeverity(item.status)"
                />
              </div>
              <div class="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-surface-600 sm:grid-cols-3">
                <span>
                  <span class="text-surface-400">{{ $t('market.payment.received.col.faceAmount') }}:</span>
                  {{ formatYen(item.faceAmount) }}
                </span>
                <span>
                  <span class="text-surface-400">{{ $t('market.payment.received.col.chargeAmount') }}:</span>
                  {{ formatYen(item.chargeAmount) }}
                </span>
                <span>
                  <span class="text-surface-400">{{ $t('market.payment.received.col.fee') }}:</span>
                  {{ formatYen(item.applicationFeeAmount) }}
                </span>
                <span>
                  <span class="text-surface-400">{{ $t('market.payment.received.col.refunded') }}:</span>
                  {{ formatYen(item.refundedAmount) }}
                </span>
                <span>
                  <span class="text-surface-400">{{ $t('market.payment.received.col.createdAt') }}:</span>
                  {{ formatDate(item.createdAt) }}
                </span>
              </div>
            </div>

            <div class="shrink-0">
              <Button
                v-if="isRefundable(item)"
                :label="$t('market.payment.received.refundButton')"
                icon="pi pi-replay"
                size="small"
                severity="secondary"
                data-testid="received-refund-btn"
                @click="openRefund(item)"
              />
              <span v-else class="text-xs text-surface-400">
                {{ $t('market.payment.received.refundUnavailable') }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <Paginator
        v-if="total > pageSize"
        :rows="pageSize"
        :total-records="total"
        :first="page * pageSize"
        class="mt-3"
        @page="onPageChange"
      />
    </template>

    <!-- 返金ダイアログ（一覧の各行から開く） -->
    <MarketRefundDialog
      v-model:visible="refundDialogVisible"
      :escrow-id="refundTargetEscrowId"
      :max-amount="refundTargetMaxAmount"
      @refunded="onRefunded"
    />
  </div>
</template>
