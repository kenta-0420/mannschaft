<script setup lang="ts">
import type { FeePreviewResponse } from '~/types/jobmatching'

/**
 * 手数料プレビューパネル。baseRewardJpy を props で受け取り、
 * debounce してから BE の {@code GET /api/v1/jobs/fee-preview} を叩いて
 * Requester 支払総額 / Worker 受取額の内訳を表示する。
 *
 * <p>500 〜 1,000,000 円の範囲外・空欄の場合はプレビューをクリアする。
 * ローディング中はスピナーのみ表示してレイアウトシフトを避ける。</p>
 */

const props = defineProps<{
  /** 業務報酬（基本額）。null / undefined / 範囲外の場合はプレビューを出さない。 */
  baseRewardJpy: number | null | undefined
  /** debounce（ms）。デフォルト 350ms。 */
  debounceMs?: number
}>()

const { t } = useI18n()
const api = useJobPostingApi()

const preview = ref<FeePreviewResponse | null>(null)
const loading = ref(false)
const debounceTimer = ref<ReturnType<typeof setTimeout> | null>(null)

function clearTimer() {
  if (debounceTimer.value !== null) {
    clearTimeout(debounceTimer.value)
    debounceTimer.value = null
  }
}

function isValid(v: number | null | undefined): v is number {
  return typeof v === 'number' && Number.isFinite(v) && v >= 500 && v <= 1_000_000
}

async function fetchPreview(value: number) {
  loading.value = true
  try {
    const res = await api.previewFee(value)
    preview.value = res.data
  }
  catch {
    preview.value = null
  }
  finally {
    loading.value = false
  }
}

watch(
  () => props.baseRewardJpy,
  (value) => {
    clearTimer()
    if (!isValid(value)) {
      preview.value = null
      loading.value = false
      return
    }
    const ms = props.debounceMs ?? 350
    debounceTimer.value = setTimeout(() => {
      fetchPreview(value)
    }, ms)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  clearTimer()
})

function fmtJpy(v: number): string {
  return `¥${v.toLocaleString()}`
}
</script>

<template>
  <div class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
    <h3 class="mb-3 text-sm font-semibold">
      {{ t('jobmatching.fee.title') }}
    </h3>

    <div
      v-if="!isValid(baseRewardJpy)"
      class="text-sm text-surface-500"
    >
      {{ t('jobmatching.fee.placeholder') }}
    </div>

    <div
      v-else-if="loading"
      class="flex justify-center py-3"
    >
      <ProgressSpinner
        style="width: 32px; height: 32px"
        stroke-width="4"
      />
    </div>

    <div
      v-else-if="preview"
      class="space-y-3 text-sm"
    >
      <section>
        <p class="mb-1 font-semibold">
          {{ t('jobmatching.fee.requester') }}
        </p>
        <dl class="grid grid-cols-[1fr_auto] gap-y-1 text-surface-700 dark:text-surface-200">
          <dt>{{ t('jobmatching.fee.baseReward') }}</dt>
          <dd class="text-right">
            {{ fmtJpy(preview.baseRewardJpy) }}
          </dd>
          <dt>{{ t('jobmatching.fee.requesterFee') }}</dt>
          <dd class="text-right">
            +{{ fmtJpy(preview.requesterFeeJpy) }}
          </dd>
          <dt>{{ t('jobmatching.fee.requesterFeeTax') }}</dt>
          <dd class="text-right">
            +{{ fmtJpy(preview.requesterFeeTaxJpy) }}
          </dd>
          <dt class="border-t border-surface-300 pt-1 font-semibold dark:border-surface-600">
            {{ t('jobmatching.fee.requesterTotal') }}
          </dt>
          <dd class="border-t border-surface-300 pt-1 text-right font-semibold dark:border-surface-600">
            {{ fmtJpy(preview.requesterTotalJpy) }}
          </dd>
        </dl>
      </section>

      <section>
        <p class="mb-1 font-semibold">
          {{ t('jobmatching.fee.worker') }}
        </p>
        <dl class="grid grid-cols-[1fr_auto] gap-y-1 text-surface-700 dark:text-surface-200">
          <dt>{{ t('jobmatching.fee.baseReward') }}</dt>
          <dd class="text-right">
            {{ fmtJpy(preview.baseRewardJpy) }}
          </dd>
          <dt>{{ t('jobmatching.fee.workerFee') }}</dt>
          <dd class="text-right">
            -{{ fmtJpy(preview.workerFeeJpy) }}
          </dd>
          <dt class="border-t border-surface-300 pt-1 font-semibold dark:border-surface-600">
            {{ t('jobmatching.fee.workerReceipt') }}
          </dt>
          <dd class="border-t border-surface-300 pt-1 text-right font-semibold dark:border-surface-600">
            {{ fmtJpy(preview.workerReceiptJpy) }}
          </dd>
        </dl>
      </section>
    </div>
  </div>
</template>
