<script setup lang="ts">
/**
 * F10.6 Phase 10-δ — 担当者セレクター。
 * SYSTEM_ADMIN ユーザーをドロップダウンで選択する改善版 UI。
 */
import type { AssignableUser } from '~/types/error-report'

const props = defineProps<{
  assigneeId: number | null
  assigneeName: string | null
  loading?: boolean
}>()

const emit = defineEmits<{
  change: [assigneeId: number | null]
}>()

const { t } = useI18n()
const { fetchAssignableUsers } = useErrorReportAdmin()

const adminUsers = ref<AssignableUser[]>([])
const fetchLoading = ref(false)
const selectedId = ref<number | null>(props.assigneeId)

watch(
  () => props.assigneeId,
  (v) => {
    selectedId.value = v
  },
)

async function loadAdminUsers() {
  fetchLoading.value = true
  try {
    const res = await fetchAssignableUsers()
    adminUsers.value = res.data
  } catch (e) {
    console.error('担当者候補の取得に失敗しました', e)
  } finally {
    fetchLoading.value = false
  }
}

function commit() {
  emit('change', selectedId.value)
}

function unassign() {
  selectedId.value = null
  emit('change', null)
}

onMounted(() => {
  void loadAdminUsers()
})
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
      <Select
        v-model="selectedId"
        :options="adminUsers"
        option-label="displayName"
        option-value="id"
        :placeholder="t('error_report.actions.select_assignee')"
        :disabled="loading || fetchLoading"
        :loading="fetchLoading"
        show-clear
        class="flex-1"
      />
      <Button
        :label="t('error_report.actions.assign')"
        size="small"
        :disabled="loading || selectedId === null"
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
