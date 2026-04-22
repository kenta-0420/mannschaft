<script setup lang="ts">
import type { JobApplicationResponse } from '~/types/jobmatching'

/**
 * 求人応募者一覧（Requester 視点）。各応募に対して accept / reject のボタンを表示する。
 *
 * <p>状態遷移のガードは BE Service 側で行われるため、FE ではボタンの有効無効のみ判定する
 * （APPLIED 以外は採用/不採用ボタンを非表示）。確認ダイアログは親または個別実装に委ねる。</p>
 */

const props = defineProps<{
  applications: JobApplicationResponse[]
  loading?: boolean
  /** 採用確定の進行中応募 ID（親から制御）。 */
  busyApplicationId?: number | null
}>()

const emit = defineEmits<{
  (e: 'accept', applicationId: number): void
  (e: 'reject', applicationId: number): void
}>()

const { t, locale } = useI18n()

function formatDateTime(iso: string): string {
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

function isBusy(applicationId: number): boolean {
  return props.busyApplicationId === applicationId
}

function canDecide(app: JobApplicationResponse): boolean {
  return app.status === 'APPLIED'
}
</script>

<template>
  <div>
    <div
      v-if="loading"
      class="flex justify-center p-6"
    >
      <ProgressSpinner />
    </div>

    <div
      v-else-if="applications.length === 0"
      class="rounded border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600"
    >
      {{ t('jobmatching.application.empty') }}
    </div>

    <ul
      v-else
      class="flex flex-col gap-3"
    >
      <li
        v-for="app in applications"
        :key="app.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0 flex-1">
            <p class="text-sm font-semibold">
              {{ t('jobmatching.application.applicantLabel', { userId: app.applicantUserId }) }}
            </p>
            <p class="mt-0.5 text-xs text-surface-500">
              {{ t('jobmatching.application.appliedAt', { at: formatDateTime(app.appliedAt) }) }}
            </p>
            <p
              v-if="app.selfPr"
              class="mt-2 whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-200"
            >
              {{ app.selfPr }}
            </p>
          </div>

          <div class="flex shrink-0 flex-col items-end gap-2">
            <JobStatusBadge
              kind="application"
              :status="app.status"
            />
            <div
              v-if="canDecide(app)"
              class="flex gap-2"
            >
              <Button
                :label="t('jobmatching.application.accept')"
                icon="pi pi-check"
                severity="success"
                size="small"
                :loading="isBusy(app.id)"
                :disabled="isBusy(app.id)"
                @click="emit('accept', app.id)"
              />
              <Button
                :label="t('jobmatching.application.reject')"
                icon="pi pi-times"
                severity="danger"
                size="small"
                outlined
                :disabled="isBusy(app.id)"
                @click="emit('reject', app.id)"
              />
            </div>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
