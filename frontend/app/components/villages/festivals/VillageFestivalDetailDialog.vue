<script setup lang="ts">
/**
 * 村お祭り詳細 Dialog — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/festivals.vue) からお祭り詳細・管理権限を受け取り、
 * バナー / バッジ / 説明文と編集・中止ボタンを描画する。
 *
 * - ロジックは持たない（操作は emit）
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'

import type { VillageFestivalResponse, VillageFestivalStatus } from '~/types/village'

defineProps<{
  visible: boolean
  festival: VillageFestivalResponse | null
  canManage: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  edit: [f: VillageFestivalResponse]
  cancelFestival: [f: VillageFestivalResponse]
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
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="festival?.title ?? ''"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="festival" class="flex flex-col gap-3">
      <div
        v-if="festival.bannerUrl"
        class="h-40 bg-surface-100 dark:bg-surface-800 overflow-hidden rounded"
      >
        <img
          :src="festival.bannerUrl"
          :alt="festival.title"
          class="w-full h-full object-cover"
        >
      </div>
      <div class="flex items-center gap-2">
        <Badge
          :value="t(`village.festival.status.${festival.status}`)"
          :severity="severityForStatus(festival.status)"
        />
        <span class="text-sm text-surface-500">
          {{ festival.startsAt }} 〜 {{ festival.endsAt }}
        </span>
      </div>
      <p v-if="festival.description" class="whitespace-pre-wrap text-sm">
        {{ festival.description }}
      </p>
    </div>
    <template #footer>
      <Button
        v-if="canManage && festival"
        :label="t('village.festival.edit')"
        icon="pi pi-pencil"
        severity="secondary"
        outlined
        @click="emit('edit', festival)"
      />
      <Button
        v-if="canManage && festival && festival.status !== 'CANCELLED' && festival.status !== 'ENDED'"
        :label="t('village.festival.cancel')"
        icon="pi pi-times"
        severity="danger"
        outlined
        @click="emit('cancelFestival', festival)"
      />
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="emit('update:visible', false)"
      />
    </template>
  </Dialog>
</template>
