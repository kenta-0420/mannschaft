<script setup lang="ts">
/**
 * F12.5 Phase 2 — 担当者セレクター。
 * 現状は数値ID直接入力 + 解除ボタンの簡易UI。
 * SYSTEM_ADMIN 検索 API は P2-C 以降で実装する。
 */

const props = defineProps<{
  assigneeId: number | null
  assigneeName: string | null
  loading?: boolean
}>()

const emit = defineEmits<{
  change: [assigneeId: number | null]
}>()

const { t } = useI18n()
const inputId = ref<number | null>(props.assigneeId)

watch(
  () => props.assigneeId,
  (v) => {
    inputId.value = v
  },
)

function commit() {
  emit('change', inputId.value)
}

function unassign() {
  inputId.value = null
  emit('change', null)
}
</script>

<template>
  <section class="space-y-2">
    <div class="flex items-center justify-between">
      <span class="text-xs font-semibold uppercase tracking-wider text-surface-500">
        {{ t('error_report.table.assignee') }}
      </span>
      <span v-if="assigneeName" class="text-xs text-surface-600 dark:text-surface-300">
        {{ assigneeName }} (#{{ assigneeId }})
      </span>
      <span v-else-if="assigneeId" class="text-xs text-surface-500">#{{ assigneeId }}</span>
      <span v-else class="text-xs text-surface-400">-</span>
    </div>
    <div class="flex gap-2">
      <InputNumber
        v-model="inputId"
        :placeholder="t('error_report.actions.select_assignee')"
        :use-grouping="false"
        :disabled="loading"
        show-buttons
        :min="1"
        class="flex-1"
      />
      <Button
        :label="t('error_report.actions.assign')"
        size="small"
        :disabled="loading || inputId === null"
        @click="commit"
      />
      <Button
        :label="t('error_report.actions.unassign')"
        size="small"
        severity="secondary"
        outlined
        :disabled="loading || assigneeId === null"
        @click="unassign"
      />
    </div>
  </section>
</template>
