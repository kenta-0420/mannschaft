<script setup lang="ts">
import type { JobContractResponse } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 契約詳細画面で表示するチェックイン状況タイムライン。
 *
 * <p>現状 BE {@code JobContractResponse} には IN/OUT 個別時刻は載っていないが、
 * 契約ステータスとチェックイン API 応答（{@code CheckInResponse#workDurationMinutes}）を
 * 画面側で合成する簡易タイムラインを提供する。将来 BE に明示フィールドが増えたら追従する。</p>
 *
 * <p>表示情報:</p>
 * <ul>
 *   <li>入場時刻（あれば） / 退場時刻（あれば）</li>
 *   <li>業務時間（分）</li>
 *   <li>{@code geoAnomaly} バッジ</li>
 * </ul>
 */

interface Props {
  /** 契約情報。 */
  contract: JobContractResponse
  /** 最新のチェックイン時刻・業務時間（親が保持）。 */
  checkIn?: {
    inAt: string | null
    outAt: string | null
    workDurationMinutes: number | null
    geoAnomaly: boolean
  } | null
}

const props = defineProps<Props>()

const { t, locale } = useI18n()

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  }
  catch {
    return iso
  }
}

const hasAny = computed(() => {
  const ci = props.checkIn
  if (!ci) return false
  return !!(ci.inAt || ci.outAt || ci.workDurationMinutes != null)
})
</script>

<template>
  <div
    class="flex flex-col gap-2 rounded-lg border border-surface-300 p-3 text-sm dark:border-surface-600"
    data-testid="check-in-status-timeline"
  >
    <h3 class="text-sm font-semibold">
      {{ t('jobmatching.qr.checkInTimeline.sectionTitle') }}
    </h3>

    <div
      v-if="!hasAny"
      class="text-xs text-surface-500"
    >
      {{ t('jobmatching.qr.checkInTimeline.empty') }}
    </div>

    <ul
      v-else
      class="flex flex-col gap-1"
    >
      <li
        v-if="checkIn?.inAt"
        class="flex items-center gap-2"
        data-testid="check-in-in-row"
      >
        <i class="pi pi-sign-in text-green-600" />
        <span class="font-medium">{{ t('jobmatching.qr.checkInTimeline.typeIn') }}</span>
        <span class="text-surface-700 dark:text-surface-200">
          {{ formatDateTime(checkIn.inAt) }}
        </span>
      </li>
      <li
        v-if="checkIn?.outAt"
        class="flex items-center gap-2"
        data-testid="check-in-out-row"
      >
        <i class="pi pi-sign-out text-red-600" />
        <span class="font-medium">{{ t('jobmatching.qr.checkInTimeline.typeOut') }}</span>
        <span class="text-surface-700 dark:text-surface-200">
          {{ formatDateTime(checkIn.outAt) }}
        </span>
      </li>
      <li
        v-if="checkIn?.workDurationMinutes != null"
        class="flex items-center gap-2"
        data-testid="check-in-duration-row"
      >
        <i class="pi pi-clock text-surface-500" />
        <span class="font-medium">{{ t('jobmatching.qr.checkInTimeline.duration') }}</span>
        <span class="text-surface-700 dark:text-surface-200">
          {{ t('jobmatching.qr.checkInTimeline.durationMinutes', { min: checkIn.workDurationMinutes }) }}
        </span>
      </li>
    </ul>

    <div
      v-if="checkIn?.geoAnomaly"
      class="mt-1 inline-flex w-fit items-center gap-1 rounded bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-900 dark:bg-amber-900/50 dark:text-amber-200"
      role="alert"
      data-testid="check-in-geo-anomaly-badge"
    >
      <i class="pi pi-exclamation-triangle" />
      {{ t('jobmatching.qr.checkInTimeline.geoAnomaly') }}
    </div>
  </div>
</template>
