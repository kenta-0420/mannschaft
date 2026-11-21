<script setup lang="ts">
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

const props = defineProps<{
  requests: ChangeRequest[]
  /** ログイン中の利用者 ID。未解決（null）の間は審査・取り下げのボタンを出さない。 */
  currentUserId: number | null
  /** 審査してよいか（チーム ADMIN / DEPUTY_ADMIN）。判定は呼び出し側の useRoleAccess。 */
  canReview?: boolean
  isLoading?: boolean
  /** 審査・取り下げの処理中の依頼 ID（二重送信防止のため disabled / loading にする） */
  processingIds?: number[]
}>()

const emit = defineEmits<{
  review: [id: number, decision: 'ACCEPTED' | 'REJECTED', comment: string]
  withdraw: [id: number]
}>()

const { t } = useI18n()

const reviewDialogVisible = ref(false)
const reviewTarget = ref<{ id: number; decision: 'ACCEPTED' | 'REJECTED' } | null>(null)
const reviewComment = ref('')

function statusSeverity(status: ChangeRequestStatus): string {
  switch (status) {
    case 'OPEN':
      return 'info'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'secondary'
  }
}

function statusLabel(status: ChangeRequestStatus): string {
  return t(`shift.changeRequest.status.${status}`)
}

function typeLabel(type: string): string {
  return t(`shift.changeRequest.type.${type}`)
}

/** 依頼者の表示名。userId は表示しない（解決できなければ「不明なユーザー」）。 */
function requesterLabel(row: ChangeRequest): string {
  return row.requesterName ?? t('shift.changeRequest.unknownRequester')
}

function isOwn(row: ChangeRequest): boolean {
  return props.currentUserId !== null && row.requestInfo.requestedBy === props.currentUserId
}

function isProcessing(id: number): boolean {
  return props.processingIds?.includes(id) ?? false
}

/** 自分の依頼は審査できない（BE も 403 SHIFT_045）。本人の確定は取り下げで代替する。 */
function canReviewRow(row: ChangeRequest): boolean {
  return !!props.canReview && props.currentUserId !== null && !isOwn(row) && row.reviewInfo.status === 'OPEN'
}

function openReviewDialog(id: number, decision: 'ACCEPTED' | 'REJECTED'): void {
  reviewTarget.value = { id, decision }
  reviewComment.value = ''
  reviewDialogVisible.value = true
}

function confirmReview(): void {
  // 確定ボタンの連打で同じ審査を重ねて送らない（対象を先に空にする）
  const target = reviewTarget.value
  if (!target) return
  reviewTarget.value = null
  reviewDialogVisible.value = false
  emit('review', target.id, target.decision, reviewComment.value.trim())
}
</script>

<template>
  <div data-testid="change-request-list">
    <PageLoading v-if="isLoading" size="32px" />

    <div v-else-if="requests.length === 0" class="py-8 text-center text-surface-400">
      <i class="pi pi-inbox mb-2 block text-3xl" />
      <p class="text-sm">{{ $t('shift.changeRequest.title') }}</p>
    </div>

    <DataTable
      v-else
      :value="requests"
      :rows="10"
      paginator
      striped-rows
      class="text-sm"
    >
      <!-- 依頼者 -->
      <Column :header="$t('shift.changeRequest.requester')" style="min-width: 100px">
        <template #body="{ data }">
          <span class="font-medium">{{ requesterLabel(data) }}</span>
        </template>
      </Column>

      <!-- 種別 -->
      <Column :header="$t('shift.changeRequest.title')" field="requestInfo.requestType" style="min-width: 120px">
        <template #body="{ data }">
          <span class="text-xs">{{ typeLabel(data.requestInfo.requestType) }}</span>
        </template>
      </Column>

      <!-- 理由 -->
      <Column :header="$t('shift.changeRequest.reason')" field="requestInfo.reason" style="min-width: 140px">
        <template #body="{ data }">
          <span class="line-clamp-1 text-xs text-surface-500">{{ data.requestInfo.reason ?? '—' }}</span>
        </template>
      </Column>

      <!-- ステータス -->
      <Column :header="$t('label.status')" field="reviewInfo.status" style="min-width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.reviewInfo.status)" :severity="statusSeverity(data.reviewInfo.status)" />
        </template>
      </Column>

      <!-- 操作 -->
      <Column :header="$t('label.actions')" style="min-width: 180px">
        <template #body="{ data }">
          <div class="flex gap-2">
            <!-- 管理者: 他人の依頼の承認・却下 -->
            <template v-if="canReviewRow(data)">
              <Button
                :data-testid="`change-request-approve-${data.id}`"
                :label="$t('shift.changeRequest.approve')"
                class="min-h-[44px]"
                severity="success"
                size="small"
                :disabled="isProcessing(data.id)"
                :loading="isProcessing(data.id)"
                @click="openReviewDialog(data.id, 'ACCEPTED')"
              />
              <Button
                :data-testid="`change-request-reject-${data.id}`"
                :label="$t('shift.changeRequest.reject')"
                class="min-h-[44px]"
                severity="danger"
                size="small"
                :disabled="isProcessing(data.id)"
                @click="openReviewDialog(data.id, 'REJECTED')"
              />
            </template>

            <!-- 依頼者本人: 取下 -->
            <Button
              v-if="isOwn(data) && data.reviewInfo.status === 'OPEN'"
              :data-testid="`change-request-withdraw-${data.id}`"
              :label="$t('shift.changeRequest.withdraw')"
              class="min-h-[44px]"
              severity="secondary"
              size="small"
              outlined
              :disabled="isProcessing(data.id)"
              :loading="isProcessing(data.id)"
              @click="emit('withdraw', data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 審査コメントダイアログ -->
    <Dialog
      v-model:visible="reviewDialogVisible"
      :header="$t('shift.changeRequest.review')"
      modal
      :style="{ width: '400px', maxWidth: '95vw' }"
    >
      <div class="space-y-3">
        <label class="block text-sm text-surface-600">
          {{ $t('shift.changeRequest.reviewComment') }}
        </label>
        <Textarea
          v-model="reviewComment"
          data-testid="change-request-review-comment"
          :placeholder="$t('shift.changeRequest.reviewComment')"
          rows="3"
          class="w-full"
        />
      </div>
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          severity="secondary"
          outlined
          @click="reviewDialogVisible = false"
        />
        <Button
          data-testid="change-request-review-confirm"
          :label="reviewTarget?.decision === 'ACCEPTED' ? $t('shift.changeRequest.approve') : $t('shift.changeRequest.reject')"
          :severity="reviewTarget?.decision === 'ACCEPTED' ? 'success' : 'danger'"
          @click="confirmReview"
        />
      </template>
    </Dialog>
  </div>
</template>
