<script setup lang="ts">
/**
 * 村寄合一覧セクション — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/meetups.vue) から寄合一覧・ステータスフィルタ・
 * 村人フラグ等を受け取り、フィルタタブ・作成ボタン・カード一覧を描画する。
 *
 * - ロジックは持たない（API 呼び出しは親が担う）
 * - 操作は emit で親に通知する
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'

import type { VillageMeetupResponse, VillageMeetupStatus } from '~/types/village'

type StatusFilter = VillageMeetupStatus | 'ALL'

defineProps<{
  meetups: VillageMeetupResponse[]
  meetupsLoading: boolean
  statusFilter: StatusFilter
  statusFilterTabs: { value: StatusFilter, i18nKey: string }[]
  isVillager: boolean
}>()

const emit = defineEmits<{
  setStatusFilter: [value: StatusFilter]
  openCreateDialog: []
  openDetailDialog: [m: VillageMeetupResponse]
}>()

const { t } = useI18n()

function severityForStatus(
  status: VillageMeetupStatus,
): 'success' | 'info' | 'danger' {
  switch (status) {
    case 'PLANNING':
      return 'success'
    case 'CONFIRMED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <!-- フィルタ + 作成ボタン -->
    <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
      <div class="flex items-center gap-1 flex-wrap">
        <button
          v-for="tab in statusFilterTabs"
          :key="tab.value"
          type="button"
          class="px-3 py-1.5 rounded-md text-sm transition"
          :class="statusFilter === tab.value
            ? 'bg-primary text-primary-contrast font-semibold'
            : 'text-surface-600 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800'"
          @click="emit('setStatusFilter', tab.value)"
        >
          {{ t(tab.i18nKey) }}
        </button>
      </div>
      <Button
        v-if="isVillager"
        :label="t('village.meetup.create')"
        icon="pi pi-plus"
        severity="primary"
        size="small"
        @click="emit('openCreateDialog')"
      />
    </div>

    <!-- 一覧 -->
    <div v-if="meetupsLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="meetups.length === 0"
      icon="pi pi-calendar-plus"
      :message="t('village.meetup.empty')"
    />
    <div v-else class="flex flex-col gap-3">
      <button
        v-for="m in meetups"
        :key="m.id"
        type="button"
        class="village-meetup__row flex flex-col gap-1 rounded-lg border border-surface-200 p-4 text-left transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
        @click="emit('openDetailDialog', m)"
      >
        <div class="flex items-center justify-between gap-2 flex-wrap">
          <div class="flex items-center gap-2">
            <Badge
              :value="t(`village.meetup.status.${m.status}`)"
              :severity="severityForStatus(m.status)"
            />
          </div>
        </div>
        <span class="font-semibold truncate">{{ m.title }}</span>
        <!--
          一覧 API は候補日を省略する（BE: listMeetups は candidateDates=null を返す）ため、
          候補日件数はここでは表示できない。確定日は MeetupResponse.confirmedDate から表示する。
        -->
        <div class="text-xs text-surface-500 flex items-center gap-3 flex-wrap">
          <span v-if="m.location">
            <i class="pi pi-map-marker mr-1" />{{ m.location }}
          </span>
          <span v-if="m.confirmedDate">
            <i class="pi pi-calendar mr-1" />
            {{ t('village.meetup.confirmedDate') }}: {{ m.confirmedDate }}
          </span>
        </div>
      </button>
    </div>
  </div>
</template>
