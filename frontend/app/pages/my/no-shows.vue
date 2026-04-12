<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { RecruitmentNoShowRecordResponse, DisputeNoShowRequest } from '~/types/recruitment'

const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const noShows = ref<RecruitmentNoShowRecordResponse[]>([])
const loading = ref(false)

// 異議申立ダイアログ
const disputeDialogVisible = ref(false)
const disputingNoShow = ref<RecruitmentNoShowRecordResponse | null>(null)
const disputeReason = ref('')
const disputeSubmitting = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.getMyNoShows()
    noShows.value = result.data
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

function statusLabel(record: RecruitmentNoShowRecordResponse): string {
  if (record.disputed) {
    if (record.disputeResolution === 'REVOKED') return t('recruitment.noShow.status.revoked')
    if (record.disputeResolution === 'UPHELD') return t('recruitment.noShow.status.upheld')
    return t('recruitment.noShow.status.disputed')
  }
  if (record.confirmed) return t('recruitment.noShow.status.confirmed')
  return t('recruitment.noShow.status.pending')
}

function statusSeverity(record: RecruitmentNoShowRecordResponse): string {
  if (record.disputed) {
    if (record.disputeResolution === 'REVOKED') return 'success'
    if (record.disputeResolution === 'UPHELD') return 'danger'
    return 'warn'
  }
  if (record.confirmed) return 'danger'
  return 'secondary'
}

function canDispute(record: RecruitmentNoShowRecordResponse): boolean {
  return record.confirmed && !record.disputed
}

function openDisputeDialog(record: RecruitmentNoShowRecordResponse) {
  disputingNoShow.value = record
  disputeReason.value = ''
  disputeDialogVisible.value = true
}

async function submitDispute() {
  if (!disputingNoShow.value) return
  if (!disputeReason.value.trim()) {
    error(t('validation.required'))
    return
  }
  disputeSubmitting.value = true
  try {
    const body: DisputeNoShowRequest = { reason: disputeReason.value.trim() }
    const result = await api.disputeNoShow(disputingNoShow.value.id, body)
    const idx = noShows.value.findIndex(n => n.id === disputingNoShow.value!.id)
    if (idx >= 0) noShows.value[idx] = result.data
    success(t('recruitment.noShow.disputeDialog.submit'))
    disputeDialogVisible.value = false
  }
  catch (e) {
    error(String(e))
  }
  finally {
    disputeSubmitting.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <h1 class="mb-4 text-2xl font-bold">
      {{ t('recruitment.noShow.pageTitle') }}
    </h1>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <div
      v-else-if="noShows.length === 0"
      class="rounded border border-dashed p-8 text-center text-gray-500"
    >
      {{ t('recruitment.label.noListings') }}
    </div>

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="record in noShows"
        :key="record.id"
        class="rounded border border-gray-200 p-4"
      >
        <div class="flex items-start justify-between gap-2">
          <div class="flex flex-col gap-1">
            <div class="text-sm text-gray-500">
              listing #{{ record.listingId }}
            </div>
            <Tag :value="statusLabel(record)" :severity="statusSeverity(record)" />
            <div v-if="record.reason" class="mt-1 text-sm text-gray-700">
              {{ record.reason }}
            </div>
            <div class="text-xs text-gray-400">
              {{ record.createdAt }}
            </div>
          </div>
          <Button
            v-if="canDispute(record)"
            :label="t('recruitment.noShow.disputeButton')"
            severity="warning"
            size="small"
            @click="openDisputeDialog(record)"
          />
        </div>
      </div>
    </div>

    <!-- 異議申立ダイアログ -->
    <Dialog
      v-model:visible="disputeDialogVisible"
      :header="t('recruitment.noShow.disputeDialog.title')"
      modal
      :style="{ width: '400px' }"
    >
      <div class="flex flex-col gap-3">
        <label class="text-sm font-medium">
          {{ t('recruitment.noShow.disputeDialog.reasonLabel') }}
        </label>
        <Textarea
          v-model="disputeReason"
          :placeholder="t('recruitment.noShow.disputeDialog.reasonPlaceholder')"
          rows="4"
          class="w-full"
        />
      </div>
      <template #footer>
        <Button
          :label="t('recruitment.action.cancel')"
          severity="secondary"
          @click="disputeDialogVisible = false"
        />
        <Button
          :label="t('recruitment.noShow.disputeDialog.submit')"
          :loading="disputeSubmitting"
          @click="submitDispute"
        />
      </template>
    </Dialog>
  </div>
</template>
