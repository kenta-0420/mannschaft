<script setup lang="ts">
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

const props = defineProps<{
  requests: ChangeRequest[]
  currentUserId: number
  isAdmin?: boolean
  isLoading?: boolean
}>()

const emit = defineEmits<{
  review: [id: number, decision: 'ACCEPTED' | 'REJECTED']
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
    case 'WITHDRAWN':
    case 'EXPIRED':
      return 'secondary'
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

function openReviewDialog(id: number, decision: 'ACCEPTED' | 'REJECTED'): void {
  reviewTarget.value = { id, decision }
  reviewComment.value = ''
  reviewDialogVisible.value = true
}

function confirmReview(): void {
  if (!reviewTarget.value) return
  emit('review', reviewTarget.value.id, reviewTarget.value.decision)
  reviewDialogVisible.value = false
}
</script>

<template>
  <div>
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
      <Column :header="$t('common.member')" field="requestedBy" style="min-width: 100px">
        <template #body="{ data }">
          <span class="font-medium">{{ data.requestedBy }}</span>
        </template>
      </Column>

      <!-- 種別 -->
      <Column :header="$t('shift.changeRequest.title')" field="requestType" style="min-width: 120px">
        <template #body="{ data }">
          <span class="text-xs">{{ typeLabel(data.requestType) }}</span>
        </template>
      </Column>

      <!-- 理由 -->
      <Column :header="$t('shift.changeRequest.reason')" field="reason" style="min-width: 140px">
        <template #body="{ data }">
          <span class="line-clamp-1 text-xs text-surface-500">{{ data.reason ?? '—' }}</span>
        </template>
      </Column>

      <!-- ステータス -->
      <Column :header="$t('common.status')" field="status" style="min-width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>

      <!-- 操作 -->
      <Column :header="$t('common.action')" style="min-width: 180px">
        <template #body="{ data }">
          <div class="flex gap-2">
            <!-- ADMIN: 承認・却下 -->
            <template v-if="isAdmin && data.status === 'OPEN'">
              <Button
                :label="$t('shift.changeRequest.approve')"
                severity="success"
                size="small"
                @click="openReviewDialog(data.id, 'ACCEPTED')"
              />
              <Button
                :label="$t('shift.changeRequest.reject')"
                severity="danger"
                size="small"
                @click="openReviewDialog(data.id, 'REJECTED')"
              />
            </template>

            <!-- 依頼者本人: 取下 -->
            <Button
              v-if="data.requestedBy === currentUserId && data.status === 'OPEN'"
              :label="$t('shift.changeRequest.withdraw')"
              severity="secondary"
              size="small"
              outlined
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
      :style="{ width: '400px' }"
    >
      <div class="space-y-3">
        <label class="block text-sm text-surface-600">
          {{ $t('shift.changeRequest.reviewComment') }}
        </label>
        <Textarea
          v-model="reviewComment"
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
          :label="reviewTarget?.decision === 'ACCEPTED' ? $t('shift.changeRequest.approve') : $t('shift.changeRequest.reject')"
          :severity="reviewTarget?.decision === 'ACCEPTED' ? 'success' : 'danger'"
          @click="confirmReview"
        />
      </template>
    </Dialog>
  </div>
</template>
