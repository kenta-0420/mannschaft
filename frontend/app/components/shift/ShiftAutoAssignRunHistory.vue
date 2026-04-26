<template>
  <div class="space-y-4">
    <h3 class="text-lg font-semibold text-surface-700">{{ $t('shift.autoAssign.history') }}</h3>

    <DataTable
      :value="runs"
      selectionMode="single"
      :meta-key-selection="false"
      class="text-sm"
      @row-click="onRowClick"
    >
      <Column field="strategy" header="戦略">
        <template #body="{ data }">
          <span>{{ data.strategy }}</span>
        </template>
      </Column>
      <Column field="startedAt" header="実行日時">
        <template #body="{ data }">
          {{ formatDate(data.startedAt) }}
        </template>
      </Column>
      <Column header="充足率">
        <template #body="{ data }">
          {{ $t('shift.autoAssign.slotsFilled', { filled: data.slotsFilled, total: data.slotsTotal }) }}
        </template>
      </Column>
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
    </DataTable>

    <!-- 詳細ドリルダウン -->
    <Dialog
      v-if="selectedRun"
      v-model:visible="detailVisible"
      header="実行詳細"
      modal
      :style="{ width: '700px' }"
    >
      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span class="text-surface-500">戦略:</span>
            <span class="ml-2 font-medium">{{ selectedRun.strategy }}</span>
          </div>
          <div>
            <span class="text-surface-500">ステータス:</span>
            <Tag :value="selectedRun.status" :severity="statusSeverity(selectedRun.status)" class="ml-2" />
          </div>
          <div>
            <span class="text-surface-500">充足率:</span>
            <span class="ml-2 font-medium">
              {{ selectedRun.slotsFilled }}/{{ selectedRun.slotsTotal }} スロット
            </span>
          </div>
          <div>
            <span class="text-surface-500">実行日時:</span>
            <span class="ml-2">{{ formatDate(selectedRun.startedAt) }}</span>
          </div>
        </div>

        <!-- 警告一覧 -->
        <div v-if="(selectedRun.warnings?.length ?? 0) > 0" class="mt-4">
          <h4 class="font-medium text-yellow-700 mb-2">
            {{ $t('shift.autoAssign.warnings', { count: selectedRun.warnings?.length ?? 0 }) }}
          </h4>
          <ul class="space-y-1">
            <li
              v-for="(w, idx) in selectedRun.warnings"
              :key="idx"
              class="text-sm text-yellow-800 bg-yellow-50 px-3 py-2 rounded"
            >
              [{{ w.code }}] {{ w.message }}
            </li>
          </ul>
        </div>

        <!-- 割当一覧 -->
        <div v-if="(selectedRun.assignments?.length ?? 0) > 0" class="mt-4">
          <h4 class="font-medium text-surface-700 mb-2">割当一覧 ({{ selectedRun.assignments?.length ?? 0 }}件)</h4>
          <DataTable :value="selectedRun.assignments" class="text-xs" :rows="10" paginator>
            <Column field="slotId" header="スロットID" />
            <Column field="userId" header="ユーザーID" />
            <Column field="status" header="ステータス" />
            <Column field="score" header="スコア">
              <template #body="{ data }">
                {{ data.score !== undefined ? data.score.toFixed(3) : '-' }}
              </template>
            </Column>
          </DataTable>
        </div>
      </div>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import type { AssignmentRun } from '~/types/shift'

interface Props {
  runs: AssignmentRun[]
}

defineProps<Props>()

const { locale } = useI18n()

const selectedRun = ref<AssignmentRun | null>(null)
const detailVisible = ref(false)

function onRowClick(event: { data: AssignmentRun }): void {
  selectedRun.value = event.data
  detailVisible.value = true
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString(locale.value)
}

function statusSeverity(
  status: string,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' | undefined {
  const map: Record<string, 'success' | 'info' | 'warn' | 'danger' | 'secondary'> = {
    RUNNING: 'info',
    SUCCEEDED: 'warn',
    FAILED: 'danger',
    CONFIRMED: 'success',
    REVOKED: 'secondary',
  }
  return map[status]
}
</script>
