<script setup lang="ts">
import { useJobCheckInApi } from '~/composables/jobs/useJobCheckInApi'
import type { CheckInResponse, JobCheckInType, JobContractResponse } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 Worker 向け QR スキャン画面。
 *
 * <p>URL: {@code /contracts/:id/scan?type=IN|OUT}（省略時は契約 status から推定）。</p>
 *
 * <p>機能:</p>
 * <ul>
 *   <li>{@link QrScanner} から @scanned を受け取り {@code recordCheckIn} を叩く</li>
 *   <li>オンライン成功 → トースト + {@code /me/jobs} に戻る</li>
 *   <li>オフラインキュー投入 → その旨のトーストを出し画面に留まる</li>
 *   <li>エラーは {@link useNotification#error} で通知</li>
 * </ul>
 */

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { success, info, error } = useNotification()
const contractApi = useJobContractApi()
const checkInApi = useJobCheckInApi()

const contractId = computed(() => Number(route.params.id))

const contract = ref<JobContractResponse | null>(null)
const loading = ref(true)
const submitting = ref(false)

/**
 * クエリ {@code type} 優先。未指定なら契約 status から推定する。
 * MATCHED → IN、CHECKED_IN / IN_PROGRESS → OUT、それ以外は IN 既定。
 */
function resolveType(): JobCheckInType {
  const raw = route.query.type
  const value = Array.isArray(raw) ? raw[0] : raw
  if (value === 'IN' || value === 'OUT') return value
  const st = contract.value?.status
  if (st === 'CHECKED_IN' || st === 'IN_PROGRESS') return 'OUT'
  return 'IN'
}

const checkInType = ref<JobCheckInType>('IN')

async function loadContract() {
  loading.value = true
  try {
    const res = await contractApi.getContract(contractId.value)
    contract.value = res.data
    checkInType.value = resolveType()
  }
  catch (e) {
    contract.value = null
    error(t('jobmatching.error.loadFailed'), String(e))
  }
  finally {
    loading.value = false
  }
}

interface ScannedEventPayload {
  token: string | null
  shortCode: string | null
  manualCodeFallback: boolean
  scannedAt: string
  geo: { lat: number | null; lng: number | null; accuracy: number | null }
  clientUserAgent: string | null
}

async function onScanned(payload: ScannedEventPayload) {
  if (submitting.value) return
  submitting.value = true
  try {
    const result = await checkInApi.recordCheckIn({
      contractId: contractId.value,
      token: payload.token,
      shortCode: payload.shortCode,
      type: checkInType.value,
      scannedAt: payload.scannedAt,
      offlineSubmitted: false,
      manualCodeFallback: payload.manualCodeFallback,
      geoLat: payload.geo.lat ?? null,
      geoLng: payload.geo.lng ?? null,
      geoAccuracy: payload.geo.accuracy ?? null,
      clientUserAgent: payload.clientUserAgent ?? null,
    })
    if (result.status === 'QUEUED') {
      info(
        t('jobmatching.qr.scanner.submitted.queuedTitle'),
        t('jobmatching.qr.scanner.submitted.queuedBody'),
      )
      return
    }
    announceSuccess(result.response)
    await router.push('/me/jobs')
  }
  catch (e) {
    error(t('jobmatching.qr.scanner.submitted.failedTitle'), String(e))
  }
  finally {
    submitting.value = false
  }
}

function announceSuccess(res: CheckInResponse) {
  if (res.type === 'IN') {
    success(t('jobmatching.qr.scanner.submitted.inSuccess'))
  }
  else {
    const min = res.workDurationMinutes ?? 0
    success(
      t('jobmatching.qr.scanner.submitted.outSuccess'),
      t('jobmatching.qr.scanner.submitted.outDetail', { min }),
    )
  }
  if (res.geoAnomaly) {
    info(
      t('jobmatching.qr.scanner.submitted.geoAnomalyTitle'),
      t('jobmatching.qr.scanner.submitted.geoAnomalyBody'),
    )
  }
}

onMounted(() => {
  loadContract()
})
</script>

<template>
  <div class="container mx-auto flex max-w-xl flex-col gap-4 p-4">
    <div class="flex items-center justify-between">
      <Button
        :label="t('jobmatching.qr.display.back')"
        icon="pi pi-arrow-left"
        severity="secondary"
        text
        @click="router.back()"
      />
      <h1 class="text-lg font-bold">
        {{ t(`jobmatching.qr.scanner.title.${checkInType}`) }}
      </h1>
    </div>

    <div
      v-if="loading"
      class="flex justify-center p-8"
    >
      <ProgressSpinner />
    </div>

    <div
      v-else-if="!contract"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      {{ t('jobmatching.qr.error.contractNotFound') }}
    </div>

    <template v-else>
      <!-- IN / OUT 切替 -->
      <div class="flex gap-2">
        <Button
          class="flex-1"
          :label="t('jobmatching.qr.scanner.switchIn')"
          :severity="checkInType === 'IN' ? 'primary' : 'secondary'"
          :outlined="checkInType !== 'IN'"
          @click="checkInType = 'IN'"
        />
        <Button
          class="flex-1"
          :label="t('jobmatching.qr.scanner.switchOut')"
          :severity="checkInType === 'OUT' ? 'primary' : 'secondary'"
          :outlined="checkInType !== 'OUT'"
          @click="checkInType = 'OUT'"
        />
      </div>

      <QrScanner
        :key="checkInType"
        :type="checkInType"
        @scanned="onScanned"
      />

      <div
        v-if="submitting"
        class="flex items-center justify-center gap-2 text-sm text-surface-500"
        data-testid="qr-scanner-submitting"
      >
        <ProgressSpinner
          style="width: 18px; height: 18px"
        />
        <span>{{ t('jobmatching.qr.scanner.submitting') }}</span>
      </div>
    </template>
  </div>
</template>
