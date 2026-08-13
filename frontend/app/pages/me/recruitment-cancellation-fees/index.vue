<script setup lang="ts">
/**
 * F03.11.1 キャンセル料の免除（設計書 §12）。
 *
 * 受取先側の管理者・受取先本人・SYSTEM_ADMIN が、自分が受け取るべきキャンセル料の
 * 記録を一覧し、理由を添えて免除できる画面。免除は BE 側で受取先の再検証を必ず行うため
 * （§10.2・二段構え）、本画面での一覧表示は「見えるかどうか」に過ぎない。
 *
 * 債務者（キャンセル料を負っている本人）向けの一覧は本画面のスコープ外
 * （設計書 §12 マスター裁定・別途起票予定）。
 */
import { onMounted, ref } from 'vue'
import type { RecruitmentCancellationRecordSummary } from '~/types/recruitment'

const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const records = ref<RecruitmentCancellationRecordSummary[]>([])
const loading = ref(false)

const waiveModalVisible = ref(false)
const waiveTarget = ref<RecruitmentCancellationRecordSummary | null>(null)
const waiving = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await api.listCancellationRecords()
    records.value = result.data
  }
  catch {
    error(t('recruitment.cancellationFeeWaive.loadError'))
  }
  finally {
    loading.value = false
  }
}

function statusLabel(status: string): string {
  const key = status
    .toLowerCase()
    .replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())
  return t(`recruitment.cancellationFeeWaive.status.${key}`)
}

function openWaiveModal(record: RecruitmentCancellationRecordSummary) {
  waiveTarget.value = record
  waiveModalVisible.value = true
}

async function onWaiveConfirm(reason: string) {
  if (!waiveTarget.value) return
  waiving.value = true
  try {
    await api.waiveCancellationFee(waiveTarget.value.id, { reason })
    success(t('recruitment.cancellationFeeWaive.waiveSuccess'))
    waiveModalVisible.value = false
    waiveTarget.value = null
    await load()
  }
  catch {
    error(t('recruitment.cancellationFeeWaive.waiveError'))
  }
  finally {
    waiving.value = false
  }
}

function onWaiveCancel() {
  waiveTarget.value = null
}

onMounted(() => load())
</script>

<template>
  <div class="container mx-auto max-w-4xl p-6">
    <PageHeader :title="t('recruitment.cancellationFeeWaive.pageTitle')" />

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="records.length === 0"
      icon="pi pi-check-circle"
      :message="t('recruitment.cancellationFeeWaive.emptyMessage')"
    />

    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="record in records"
        :key="record.id"
        class="flex items-center justify-between gap-4"
      >
        <div class="flex flex-col gap-1">
          <div class="font-medium">
            {{ record.listingTitle }}
          </div>
          <div class="text-sm text-surface-500">
            {{ t('recruitment.cancellationFeeWaive.columns.user') }}: #{{ record.userId }}
            /
            {{ t('recruitment.cancellationFeeWaive.columns.cancelledAt') }}: {{ record.cancelledAt }}
          </div>
          <div class="mt-1 flex items-center gap-2">
            <Tag :value="statusLabel(record.paymentStatus)" />
            <span class="text-lg font-bold text-red-700">
              ¥{{ record.feeAmount.toLocaleString() }}
            </span>
          </div>
        </div>
        <Button
          :label="t('recruitment.cancellationFeeWaive.waiveButton')"
          severity="danger"
          size="small"
          @click="openWaiveModal(record)"
        />
      </SectionCard>
    </div>

    <RecruitmentCancellationFeeWaiveModal
      v-model:visible="waiveModalVisible"
      :record="waiveTarget"
      :loading="waiving"
      @confirm="onWaiveConfirm"
      @cancel="onWaiveCancel"
    />
  </div>
</template>
