<script setup lang="ts">
import type { JobCheckInType, JobContractResponse } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 QR 表示画面（Requester 向け）。
 *
 * <p>URL: {@code /contracts/:id/qr?type=IN|OUT}。クエリ {@code type} 未指定時は {@code IN} として動作する。</p>
 *
 * <p>Requester が契約相手の Worker にチェックイン QR を見せるための専用画面。
 * 契約が自身の Requester 契約でない場合は BE 認可で弾かれるため、画面側では簡易フェイルセーフのみ行う。</p>
 */

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { error } = useNotification()
const contractApi = useJobContractApi()

const contractId = computed(() => Number(route.params.id))

function parseType(): JobCheckInType {
  const raw = route.query.type
  const value = Array.isArray(raw) ? raw[0] : raw
  return value === 'OUT' ? 'OUT' : 'IN'
}

const checkInType = ref<JobCheckInType>(parseType())
const contract = ref<JobContractResponse | null>(null)
const loading = ref(true)

/** クエリ切替時に type を再評価する（ルート変更に追従）。 */
watch(() => route.query.type, () => {
  checkInType.value = parseType()
})

async function loadContract() {
  loading.value = true
  try {
    const res = await contractApi.getContract(contractId.value)
    contract.value = res.data
  }
  catch (e) {
    contract.value = null
    error(t('jobmatching.error.loadFailed'), String(e))
  }
  finally {
    loading.value = false
  }
}

function switchType(next: JobCheckInType) {
  router.replace({ query: { ...route.query, type: next } })
}

onMounted(() => {
  loadContract()
})
</script>

<template>
  <div class="container mx-auto flex max-w-xl flex-col items-center p-4">
    <!-- 戻るボタン -->
    <div class="mb-3 w-full">
      <Button
        :label="t('jobmatching.qr.display.back')"
        icon="pi pi-arrow-left"
        severity="secondary"
        text
        @click="router.back()"
      />
    </div>

    <!-- IN / OUT 切替タブ -->
    <div class="mb-4 flex w-full gap-2">
      <Button
        class="flex-1"
        :label="t('jobmatching.qr.display.switchIn')"
        :severity="checkInType === 'IN' ? 'primary' : 'secondary'"
        :outlined="checkInType !== 'IN'"
        @click="switchType('IN')"
      />
      <Button
        class="flex-1"
        :label="t('jobmatching.qr.display.switchOut')"
        :severity="checkInType === 'OUT' ? 'primary' : 'secondary'"
        :outlined="checkInType !== 'OUT'"
        @click="switchType('OUT')"
      />
    </div>

    <!-- 契約ロード中 -->
    <div
      v-if="loading"
      class="flex justify-center p-8"
    >
      <ProgressSpinner />
    </div>

    <!-- 契約取得失敗 -->
    <div
      v-else-if="!contract"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      {{ t('jobmatching.qr.error.contractNotFound') }}
    </div>

    <!-- QR 表示本体 -->
    <QrCheckInDisplay
      v-else
      :key="`${contract.id}-${checkInType}`"
      :contract-id="contract.id"
      :type="checkInType"
    />
  </div>
</template>
