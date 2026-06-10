<script setup lang="ts">
/**
 * F22.1 / F03.11: 札主の応募者管理（成立＝CONFIRM）パネル。
 *
 * 札主（募集主）が応募者一覧を見て成立（APPLIED → CONFIRMED）させる。謝礼あり札
 * （paymentEnabled）では、成立の前に謝礼のお支払い（カード与信）を確認するステップを挟む
 * （MarketEscrowConfirmDialog）。確認が完了したら confirmApplication を呼ぶ。
 *
 * 受取側の返金（受取側 ADMIN 操作）も本パネルから行えるよう、成立済み参加者には
 * MarketRefundDialog を配線する。返金対象のエスクロー ID は
 * getRecruitmentPaymentIntent で listingId/participantId から解決する
 * （受取側エスクロー一覧 EP が BE 未実装のため・本配線は単一照会で代替）。
 *
 * BE 配線（recon 済み・実在 EP）:
 *   - GET   /api/v1/recruitment-listings/{listingId}/participants
 *   - POST  /api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm
 *   - GET   /api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent
 *   - POST  /api/v1/payment/escrow/{id}/refund
 */
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import type { RecruitmentParticipantResponse } from '~/types/recruitment'

interface Props {
  listingId: number
  /** 謝礼あり札か（成立前の決済確認を挟むかの判定）。 */
  paymentEnabled: boolean
}

const props = defineProps<Props>()

const { t } = useI18n()
const recruitmentApi = useRecruitmentApi()
const marketPaymentApi = useMarketPaymentApi()
const { success, error } = useNotification()

const participants = ref<RecruitmentParticipantResponse[]>([])
const loading = ref(false)
const confirmingId = ref<number | null>(null)

// 決済確認ダイアログ。
const escrowDialogVisible = ref(false)
const escrowParticipantId = ref<number | null>(null)

// 返金ダイアログ。
const refundDialogVisible = ref(false)
const refundEscrowId = ref<string | null>(null)
const resolvingRefundFor = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await recruitmentApi.listListingParticipants(props.listingId)
    participants.value = res.data
  } catch (e) {
    error(String(e))
  } finally {
    loading.value = false
  }
}

function statusLabel(s: string): string {
  return t(`recruitment.participantStatus.${s.toLowerCase()}`)
}

/** 成立ボタン押下。謝礼あり札なら決済確認ダイアログを先に出す。 */
function onConfirmClick(p: RecruitmentParticipantResponse) {
  if (props.paymentEnabled) {
    escrowParticipantId.value = p.id
    escrowDialogVisible.value = true
    return
  }
  void doConfirm(p.id)
}

/** 決済確認（与信 or DEFERRED/AUTHORIZED）完了 → 成立を実行する。 */
function onEscrowConfirmed() {
  const pid = escrowParticipantId.value
  escrowDialogVisible.value = false
  escrowParticipantId.value = null
  if (pid != null) {
    void doConfirm(pid)
  }
}

async function doConfirm(participantId: number) {
  if (confirmingId.value != null) {
    return
  }
  confirmingId.value = participantId
  try {
    await recruitmentApi.confirmApplication(props.listingId, participantId)
    success(t('recruitment.action.confirmApplication'))
    await load()
  } catch (e) {
    error(String(e))
  } finally {
    confirmingId.value = null
  }
}

/**
 * 返金ボタン押下。受取側エスクロー一覧 EP が無いため、payment-intent 照会で
 * escrowTransactionId を解決してから返金ダイアログを開く。
 */
async function onRefundClick(p: RecruitmentParticipantResponse) {
  if (resolvingRefundFor.value != null) {
    return
  }
  resolvingRefundFor.value = p.id
  try {
    const res = await marketPaymentApi.getRecruitmentPaymentIntent(props.listingId, p.id)
    refundEscrowId.value = res.data.escrowTransactionId
    refundDialogVisible.value = true
  } catch (e) {
    error(String(e))
  } finally {
    resolvingRefundFor.value = null
  }
}

function onRefunded() {
  refundDialogVisible.value = false
  refundEscrowId.value = null
  void load()
}

onMounted(load)
</script>

<template>
  <section class="flex flex-col gap-3">
    <h2 class="text-lg font-semibold">
      {{ t('recruitment.label.participants') }}
    </h2>

    <div v-if="loading" class="flex justify-center p-6">
      <LoadingBounce />
    </div>

    <div
      v-else-if="participants.length === 0"
      class="rounded border border-dashed p-6 text-center text-gray-500"
    >
      {{ t('recruitment.label.noParticipants') }}
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="p in participants"
        :key="p.id"
        class="flex items-center justify-between rounded border border-gray-200 p-3"
      >
        <div class="flex flex-col">
          <div class="text-sm text-gray-500">
            #{{ p.id }}
          </div>
          <div class="mt-1">
            <Tag :value="statusLabel(p.status)" />
          </div>
        </div>
        <div class="flex gap-2">
          <Button
            v-if="p.status === 'APPLIED' || p.status === 'WAITLISTED'"
            :label="t('recruitment.action.confirmApplication')"
            icon="pi pi-check"
            size="small"
            :loading="confirmingId === p.id"
            @click="onConfirmClick(p)"
          />
          <Button
            v-if="paymentEnabled && p.status === 'CONFIRMED'"
            :label="t('market.payment.refund.title')"
            icon="pi pi-undo"
            size="small"
            severity="secondary"
            :loading="resolvingRefundFor === p.id"
            @click="onRefundClick(p)"
          />
        </div>
      </div>
    </div>

    <!-- 札主の決済確認（成立前のカード与信） -->
    <MarketEscrowConfirmDialog
      v-if="escrowParticipantId != null"
      v-model:visible="escrowDialogVisible"
      :listing-id="listingId"
      :participant-id="escrowParticipantId"
      @confirmed="onEscrowConfirmed"
    />

    <!-- 受取側 ADMIN の返金 -->
    <MarketRefundDialog
      v-if="refundEscrowId"
      v-model:visible="refundDialogVisible"
      :escrow-id="refundEscrowId"
      @refunded="onRefunded"
    />
  </section>
</template>
