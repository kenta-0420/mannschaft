<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { RecruitmentNoShowRecordResponse, ResolveDisputeRequest } from '~/types/recruitment'

const route = useRoute()
const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const scopeType = computed(() => String(route.params.scopeType))
const scopeId = computed(() => Number(route.params.scopeId))

const noShows = ref<RecruitmentNoShowRecordResponse[]>([])
const loading = ref(false)
const page = ref(0)
const pageSize = ref(20)
const totalElements = ref(0)

// 異議解決ダイアログ
const resolveDialogVisible = ref(false)
const resolvingNoShow = ref<RecruitmentNoShowRecordResponse | null>(null)
const resolveResolution = ref<'REVOKED' | 'UPHELD'>('REVOKED')
const resolveAdminNote = ref('')
const resolveSubmitting = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.getNoShowsByScope(
      scopeType.value,
      scopeId.value,
      page.value,
      pageSize.value,
    )
    noShows.value = result.data
    totalElements.value = result.meta.totalElements
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

function canResolve(record: RecruitmentNoShowRecordResponse): boolean {
  return record.disputed && record.disputeResolution === null
}

function openResolveDialog(record: RecruitmentNoShowRecordResponse) {
  resolvingNoShow.value = record
  resolveResolution.value = 'REVOKED'
  resolveAdminNote.value = ''
  resolveDialogVisible.value = true
}

async function submitResolve() {
  if (!resolvingNoShow.value) return
  resolveSubmitting.value = true
  try {
    const body: ResolveDisputeRequest = {
      resolution: resolveResolution.value,
      adminNote: resolveAdminNote.value.trim() || undefined,
    }
    const result = await api.resolveDispute(
      scopeType.value,
      scopeId.value,
      resolvingNoShow.value.id,
      body,
    )
    const idx = noShows.value.findIndex(n => n.id === resolvingNoShow.value!.id)
    if (idx >= 0) noShows.value[idx] = result.data
    success(t('recruitment.noShow.resolveDialog.submit'))
    resolveDialogVisible.value = false
  }
  catch (e) {
    error(String(e))
  }
  finally {
    resolveSubmitting.value = false
  }
}

async function onPageChange(event: { page: number }) {
  page.value = event.page
  await load()
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <PageHeader :title="t('recruitment.noShow.adminPageTitle')" />

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
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium">ID: {{ record.id }}</span>
              <Tag :value="statusLabel(record)" :severity="statusSeverity(record)" />
            </div>
            <div class="text-sm text-gray-600">
              listing #{{ record.listingId }} / participant #{{ record.participantId }}
            </div>
            <div v-if="record.reason" class="text-sm text-gray-700">
              {{ record.reason }}
            </div>
            <div class="text-xs text-gray-400">
              {{ record.createdAt }}
            </div>
          </div>
          <Button
            v-if="canResolve(record)"
            :label="t('recruitment.noShow.resolveDialog.title')"
            severity="warning"
            size="small"
            @click="openResolveDialog(record)"
          />
        </div>
      </div>
    </div>

    <Paginator
      v-if="totalElements > pageSize"
      :rows="pageSize"
      :total-records="totalElements"
      class="mt-4"
      @page="onPageChange"
    />

    <!-- 異議解決ダイアログ -->
    <Dialog
      v-model:visible="resolveDialogVisible"
      :header="t('recruitment.noShow.resolveDialog.title')"
      modal
      :style="{ width: '420px' }"
    >
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label class="text-sm font-medium">{{ t('recruitment.noShow.resolveDialog.title') }}</label>
          <div class="flex gap-3">
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="resolveResolution"
                input-id="revoke"
                value="REVOKED"
              />
              <label for="revoke" class="text-sm">{{ t('recruitment.noShow.resolveDialog.revoke') }}</label>
            </div>
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="resolveResolution"
                input-id="uphold"
                value="UPHELD"
              />
              <label for="uphold" class="text-sm">{{ t('recruitment.noShow.resolveDialog.uphold') }}</label>
            </div>
          </div>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('recruitment.noShow.resolveDialog.adminNote') }}</label>
          <Textarea
            v-model="resolveAdminNote"
            rows="3"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('recruitment.action.cancel')"
          severity="secondary"
          @click="resolveDialogVisible = false"
        />
        <Button
          :label="t('recruitment.noShow.resolveDialog.submit')"
          :loading="resolveSubmitting"
          @click="submitResolve"
        />
      </template>
    </Dialog>
  </div>
</template>
