<script setup lang="ts">
import type { JobPostingSummaryResponse } from '~/types/jobmatching'

/**
 * 求人一覧カード。Requester / Worker 双方の一覧で共用する。
 *
 * <p>クリック時にカード全体が詳細画面への導線となる想定（親側で NuxtLink で包むか
 * @click を拾う）。ステータスは {@link JobStatusBadge} で表示。</p>
 */

defineProps<{
  job: JobPostingSummaryResponse
  /** 業務場所タイプのローカライズ済みラベルを親から渡したい場合に使用（省略時は enum 値）。 */
  workLocationLabel?: string
}>()

const { t, locale } = useI18n()

function formatDateTime(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString(locale.value, {
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

function fmtJpy(v: number): string {
  return `¥${v.toLocaleString()}`
}
</script>

<template>
  <article
    class="rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <h3 class="truncate text-base font-semibold">
          {{ job.title }}
        </h3>
        <p
          v-if="job.category"
          class="mt-0.5 text-xs text-surface-500"
        >
          {{ job.category }}
        </p>
      </div>

      <div class="flex shrink-0 flex-col items-end gap-1">
        <JobStatusBadge
          kind="posting"
          :status="job.status"
        />
        <span class="text-sm font-semibold">
          {{ fmtJpy(job.baseRewardJpy) }}
        </span>
      </div>
    </div>

    <dl class="mt-3 grid grid-cols-1 gap-y-1 text-xs text-surface-600 dark:text-surface-300 sm:grid-cols-2">
      <div class="flex items-center gap-1">
        <i class="pi pi-calendar text-xs" />
        <dt class="sr-only">
          {{ t('jobmatching.card.workAt') }}
        </dt>
        <dd>
          {{ formatDateTime(job.workStartAt) }} - {{ formatDateTime(job.workEndAt) }}
        </dd>
      </div>
      <div class="flex items-center gap-1">
        <i class="pi pi-map-marker text-xs" />
        <dt class="sr-only">
          {{ t('jobmatching.card.workLocationType') }}
        </dt>
        <dd>
          {{ workLocationLabel ?? t(`jobmatching.workLocationType.${job.workLocationType}`) }}
        </dd>
      </div>
      <div class="flex items-center gap-1">
        <i class="pi pi-users text-xs" />
        <dt class="sr-only">
          {{ t('jobmatching.card.capacity') }}
        </dt>
        <dd>
          {{ t('jobmatching.card.capacityValue', { count: job.capacity }) }}
        </dd>
      </div>
      <div class="flex items-center gap-1">
        <i class="pi pi-clock text-xs" />
        <dt class="sr-only">
          {{ t('jobmatching.card.deadline') }}
        </dt>
        <dd>
          {{ t('jobmatching.card.deadlineValue', { at: formatDateTime(job.applicationDeadlineAt) }) }}
        </dd>
      </div>
    </dl>
  </article>
</template>
