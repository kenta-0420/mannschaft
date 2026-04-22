<script setup lang="ts">
import type { JobContractResponse } from '~/types/jobmatching'

/**
 * 契約詳細画面で役割別にアクションを出し分けるパネル。
 *
 * <p>Worker（= {@code contract.workerUserId} 本人）: 完了報告 / キャンセル<br>
 * Requester（= {@code contract.requesterUserId} 本人）: 完了承認 / 完了差し戻し / キャンセル</p>
 *
 * <p>どのアクションが押せるかは契約のステータスによって細かく変わるが、
 * 最終的な状態遷移ガードは BE 側に任せる（StateMachine + Policy）。
 * FE ではボタンの有効無効のみざっくり制御する。</p>
 */

interface Props {
  contract: JobContractResponse
  /** 現在ログイン中のユーザー ID。 */
  currentUserId: number
  /** 進行中アクションのキー（親から制御）。 */
  busyAction?: ContractActionKind | null
}

export type ContractActionKind =
  | 'report-completion'
  | 'approve-completion'
  | 'reject-completion'
  | 'cancel'

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'report-completion'): void
  (e: 'approve-completion'): void
  (e: 'reject-completion'): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()

const isWorker = computed(() => props.contract.workerUserId === props.currentUserId)
const isRequester = computed(() => props.contract.requesterUserId === props.currentUserId)

const status = computed(() => props.contract.status)

// Worker が完了報告できる（MVP では MATCHED 系から COMPLETION_REPORTED に遷移）
const canReport = computed(() =>
  isWorker.value
  && (status.value === 'MATCHED'
    || status.value === 'CHECKED_IN'
    || status.value === 'IN_PROGRESS'
    || status.value === 'CHECKED_OUT'
    || status.value === 'TIME_CONFIRMED'),
)

// Requester が承認・差し戻しできる（COMPLETION_REPORTED のみ）
const canApprove = computed(() => isRequester.value && status.value === 'COMPLETION_REPORTED')
const canReject = computed(() => isRequester.value && status.value === 'COMPLETION_REPORTED')

// キャンセル可能（Requester / Worker 本人、終端状態以外）
const isTerminal = computed(() =>
  status.value === 'COMPLETED'
  || status.value === 'PAID'
  || status.value === 'CANCELLED'
  || status.value === 'DISPUTED',
)
const canCancel = computed(() => (isRequester.value || isWorker.value) && !isTerminal.value)

function isBusy(kind: ContractActionKind): boolean {
  return props.busyAction === kind
}

const hasAnyAction = computed(
  () => canReport.value || canApprove.value || canReject.value || canCancel.value,
)
</script>

<template>
  <div
    v-if="hasAnyAction"
    class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
  >
    <h3 class="mb-3 text-sm font-semibold">
      {{ t('jobmatching.contract.actions.title') }}
    </h3>

    <div class="flex flex-wrap gap-2">
      <Button
        v-if="canReport"
        :label="t('jobmatching.contract.actions.reportCompletion')"
        icon="pi pi-send"
        :loading="isBusy('report-completion')"
        :disabled="isBusy('report-completion')"
        @click="emit('report-completion')"
      />
      <Button
        v-if="canApprove"
        :label="t('jobmatching.contract.actions.approveCompletion')"
        icon="pi pi-check"
        severity="success"
        :loading="isBusy('approve-completion')"
        :disabled="isBusy('approve-completion')"
        @click="emit('approve-completion')"
      />
      <Button
        v-if="canReject"
        :label="t('jobmatching.contract.actions.rejectCompletion')"
        icon="pi pi-replay"
        severity="warn"
        outlined
        :disabled="isBusy('reject-completion')"
        @click="emit('reject-completion')"
      />
      <Button
        v-if="canCancel"
        :label="t('jobmatching.contract.actions.cancel')"
        icon="pi pi-times"
        severity="danger"
        outlined
        :disabled="isBusy('cancel')"
        @click="emit('cancel')"
      />
    </div>
  </div>
</template>
