<script setup lang="ts">
import type { AdvanceNoticeResponse } from '~/types/care'

/**
 * F03.12 §15 主催者・チーム ADMIN 向け事前通知一覧。
 *
 * <p>{@code GET /api/v1/teams/{teamId}/events/{eventId}/advance-notices}
 * の結果をテーブル表示する。空の場合はプレースホルダ文言を表示。</p>
 */

const props = defineProps<{
  teamId: number
  eventId: number
}>()

const { t, locale } = useI18n()
const teamIdRef = computed(() => props.teamId)
const eventIdRef = computed(() => props.eventId)
const { notices, loading, error, loadAdvanceNotices } = useAdvanceNotice(teamIdRef, eventIdRef)

onMounted(() => {
  loadAdvanceNotices()
})

watch([teamIdRef, eventIdRef], () => {
  loadAdvanceNotices()
})

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(d)
}

function reasonLabel(notice: AdvanceNoticeResponse): string {
  if (notice.noticeType === 'LATE') {
    const m = notice.expectedArrivalMinutesLate ?? 0
    return t('event.advanceNotice.lateMinutesLabel', { minutes: m })
  }
  if (notice.absenceReason) {
    return t(`event.advanceNotice.absenceReason.${notice.absenceReason}`)
  }
  return ''
}
</script>

<template>
  <div
    class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900"
    data-testid="advance-notice-list"
  >
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-base font-semibold">
        {{ t('event.advanceNotice.listTitle') }}
      </h3>
      <Button
        icon="pi pi-refresh"
        text
        size="small"
        :loading="loading"
        :aria-label="t('common.reload')"
        data-testid="advance-notice-list-reload"
        @click="loadAdvanceNotices"
      />
    </div>

    <p
      v-if="error"
      class="mb-2 text-sm text-red-600"
      data-testid="advance-notice-list-error"
    >
      {{ error }}
    </p>

    <div v-if="!loading && notices.length === 0" class="text-sm text-surface-500">
      {{ t('event.advanceNotice.listEmpty') }}
    </div>

    <table
      v-else
      class="w-full text-sm"
      data-testid="advance-notice-list-table"
    >
      <thead>
        <tr class="border-b border-surface-200 text-left text-xs text-surface-500 dark:border-surface-700">
          <th class="px-2 py-2">
            {{ t('event.advanceNotice.colType') }}
          </th>
          <th class="px-2 py-2">
            {{ t('event.advanceNotice.colSubject') }}
          </th>
          <th class="px-2 py-2">
            {{ t('event.advanceNotice.colReason') }}
          </th>
          <th class="px-2 py-2">
            {{ t('event.advanceNotice.colComment') }}
          </th>
          <th class="px-2 py-2">
            {{ t('event.advanceNotice.colCreatedAt') }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(n, idx) in notices"
          :key="`${n.userId}-${n.createdAt}-${idx}`"
          class="border-b border-surface-100 last:border-b-0 dark:border-surface-800"
        >
          <td class="px-2 py-2">
            <span
              v-if="n.noticeType === 'LATE'"
              class="inline-flex items-center rounded bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300"
            >
              {{ t('event.advanceNotice.badgeLate') }}
            </span>
            <span
              v-else
              class="inline-flex items-center rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800 dark:bg-red-900/30 dark:text-red-300"
            >
              {{ t('event.advanceNotice.badgeAbsence') }}
            </span>
          </td>
          <td class="px-2 py-2">
            {{ n.displayName }}
          </td>
          <td class="px-2 py-2">
            {{ reasonLabel(n) }}
          </td>
          <td class="px-2 py-2 text-surface-600 dark:text-surface-300">
            {{ n.comment ?? '' }}
          </td>
          <td class="px-2 py-2 text-xs text-surface-500">
            {{ formatDateTime(n.createdAt) }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
