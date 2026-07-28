<script setup lang="ts">
/**
 * 村お祭り一覧セクション — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/festivals.vue) からお祭り一覧・ステータスフィルタ・
 * 管理権限フラグ等を受け取り、フィルタタブ・企画ボタン・カード一覧を描画する。
 *
 * - ロジックは持たない（API 呼び出しは親が担う）
 * - 操作は emit で親に通知する
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'

import type { VillageFestivalResponse, VillageFestivalStatus } from '~/types/village'

type StatusFilter = VillageFestivalStatus | 'ALL'

defineProps<{
  festivals: VillageFestivalResponse[]
  festivalsLoading: boolean
  statusFilter: StatusFilter
  statusFilterTabs: { value: StatusFilter, i18nKey: string }[]
  canManage: boolean
}>()

const emit = defineEmits<{
  setStatusFilter: [value: StatusFilter]
  openCreateDialog: []
  openDetailDialog: [f: VillageFestivalResponse]
}>()

const { t } = useI18n()

function severityForStatus(status: VillageFestivalStatus): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'SCHEDULED':
      return 'info'
    case 'ENDED':
      return 'secondary'
    case 'CANCELLED':
      return 'danger'
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <!-- ステータスフィルタ + 企画ボタン -->
    <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
      <div class="flex items-center gap-2 flex-wrap">
        <Button
          v-for="tab in statusFilterTabs"
          :key="tab.value"
          :label="t(tab.i18nKey)"
          size="small"
          :severity="statusFilter === tab.value ? 'primary' : 'secondary'"
          :outlined="statusFilter !== tab.value"
          @click="emit('setStatusFilter', tab.value)"
        />
      </div>
      <Button
        v-if="canManage"
        :label="t('village.festival.create')"
        icon="pi pi-plus"
        severity="primary"
        size="small"
        @click="emit('openCreateDialog')"
      />
    </div>

    <!-- お祭り一覧 -->
    <div v-if="festivalsLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="festivals.length === 0"
      icon="pi pi-star"
      :message="t('village.festival.empty')"
    />
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-4">
      <button
        v-for="f in festivals"
        :key="f.id"
        type="button"
        class="village-festival__card flex flex-col rounded-lg border border-surface-200 overflow-hidden text-left transition hover:shadow-md dark:border-surface-700"
        :style="f.themeColorHex ? { borderTop: `4px solid ${f.themeColorHex}` } : undefined"
        @click="emit('openDetailDialog', f)"
      >
        <div class="h-28 bg-surface-100 dark:bg-surface-800 flex items-center justify-center overflow-hidden">
          <img
            v-if="f.bannerUrl"
            :src="f.bannerUrl"
            :alt="f.title"
            class="w-full h-full object-cover"
          >
          <span v-else class="text-surface-400 text-sm">
            <i class="pi pi-image" /> {{ t('village.festival.noBanner') }}
          </span>
        </div>
        <div class="p-3 flex flex-col gap-1">
          <div class="flex items-center justify-between gap-2">
            <span class="font-semibold truncate">{{ f.title }}</span>
            <Badge
              :value="t(`village.festival.status.${f.status}`)"
              :severity="severityForStatus(f.status)"
            />
          </div>
          <div class="text-xs text-surface-500">
            {{ f.startsAt }} 〜 {{ f.endsAt }}
          </div>
        </div>
      </button>
    </div>
  </div>
</template>
