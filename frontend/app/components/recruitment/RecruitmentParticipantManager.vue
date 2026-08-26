<script setup lang="ts">
/**
 * F22.1 / F03.11: 札主の応募者管理（成立＝CONFIRM）パネル。
 *
 * 札主（募集主）が応募者一覧を見て成立（APPLIED → CONFIRMED）させる。謝礼あり札
 * （paymentEnabled）では、成立の前に謝礼のお支払い（カード与信）を確認するステップを挟む
 * （MarketEscrowConfirmDialog）。確認が完了したら confirmApplication を呼ぶ。
 *
 * 注: 返金は受取側（payee）の ADMIN 操作であり、本パネルは札主（payer）の画面のため
 * 返金導線は配線しない。返金 UI は受取側ページ pages/me/recruitment-payments.vue に
 * 一本化する。
 *
 * BE 配線（recon 済み・実在 EP）:
 *   - GET   /api/v1/recruitment-listings/{listingId}/participants
 *   - POST  /api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm
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
const { success, error } = useNotification()

const participants = ref<RecruitmentParticipantResponse[]>([])
const loading = ref(false)
const confirmingId = ref<number | null>(null)

// 決済確認ダイアログ。
const escrowDialogVisible = ref(false)
const escrowParticipantId = ref<number | null>(null)

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
  </section>
</template>
