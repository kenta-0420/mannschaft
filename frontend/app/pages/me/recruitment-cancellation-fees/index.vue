<script setup lang="ts">
/**
 * F03.11.1 キャンセル料の免除（設計書 §12）。
 *
 * 受取先側の管理者・受取先本人・SYSTEM_ADMIN が、自分が受け取るべきキャンセル料の
 * 記録を一覧し、理由を添えて免除できる画面。一覧に出る記録は BE 側で escrow（権威ある受取先）
 * によって絞られており、免除の実行時にも同じ判定で再検証される（§10.2）。
 *
 * 債務者（キャンセル料を負っている本人）向けの一覧は本画面のスコープ外
 * （設計書 §12 マスター裁定・別途起票予定）。
 *
 * ページングはカーソル方式（キーセット）である。免除すると母集合が縮むため、ページ番号送りだと
 * 行が読み飛ばされる。「もっと見る」で nextCursor を辿って積み増す形にしている。
 */
import { onMounted, ref } from 'vue'
import { formatDateTime } from '~/utils/eventFormat'
import type { RecruitmentCancellationRecordSummary } from '~/types/recruitment'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const records = ref<RecruitmentCancellationRecordSummary[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const nextCursor = ref<string | null>(null)
const hasNext = ref(false)

const waiveModalVisible = ref(false)
const waiveTarget = ref<RecruitmentCancellationRecordSummary | null>(null)
const waiving = ref(false)

/** 先頭から読み直す（初回表示・免除後のリフレッシュ）。 */
async function load() {
  loading.value = true
  try {
    const result = await api.listCancellationRecords(undefined, null)
    records.value = result.data
    nextCursor.value = result.meta.nextCursor
    hasNext.value = result.meta.hasNext
  }
  catch {
    error(t('recruitment.cancellationFeeWaive.loadError'))
  }
  finally {
    loading.value = false
  }
}

/** 続きを読み込んで末尾に積む。 */
async function loadMore() {
  if (!hasNext.value || !nextCursor.value) return
  loadingMore.value = true
  try {
    const result = await api.listCancellationRecords(undefined, nextCursor.value)
    records.value = [...records.value, ...result.data]
    nextCursor.value = result.meta.nextCursor
    hasNext.value = result.meta.hasNext
  }
  catch {
    error(t('recruitment.cancellationFeeWaive.loadError'))
  }
  finally {
    loadingMore.value = false
  }
}

function statusLabel(status: string): string {
  const key = status
    .toLowerCase()
    .replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())
  return t(`recruitment.cancellationFeeWaive.status.${key}`)
}

/**
 * 対象ユーザーの表示。userId は DB 上 nullable（退会等で NULL になる）ため、
 * null のときは ID を出さず「不明」と表示する（画面を壊さない）。
 */
function userLabel(record: RecruitmentCancellationRecordSummary): string {
  return record.userId === null
    ? t('recruitment.cancellationFeeWaive.unknownUser')
    : `#${record.userId}`
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
    // 免除した行は既定の絞り込みから外れる。先頭から読み直して一覧を整合させる。
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
            {{ t('recruitment.cancellationFeeWaive.columns.user') }}: {{ userLabel(record) }}
            /
            {{ t('recruitment.cancellationFeeWaive.columns.cancelledAt') }}:
            {{ formatDateTime(record.cancelledAt) }}
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

      <div v-if="hasNext" class="flex justify-center pt-2">
        <Button
          :label="t('recruitment.cancellationFeeWaive.loadMore')"
          severity="secondary"
          outlined
          :loading="loadingMore"
          data-testid="waive-load-more"
          @click="loadMore"
        />
      </div>
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
