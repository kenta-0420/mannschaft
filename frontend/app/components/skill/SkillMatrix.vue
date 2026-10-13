<script setup lang="ts">
import type { SkillMatrixCell, SkillMatrixResponse, SkillMatrixRow, SkillStatus } from '~/types/skill'

const props = defineProps<{
  teamId: string
}>()

const { getSkillMatrix } = useSkillApi()
const notification = useNotification()
const { t } = useI18n()

const matrixData = ref<SkillMatrixResponse | null>(null)
const loading = ref(false)

const categories = computed(() => matrixData.value?.categories ?? [])
const rows = computed<SkillMatrixRow[]>(() => matrixData.value?.rows ?? [])

/**
 * セルの状態。cells にキーが無い・status が null は「未登録」(NONE)。
 * 確認待ち（PENDING_REVIEW）はマトリクスでは未登録として扱う（BE 仕様: F07.5 matrix 節）ため、独立した状態を持たない。
 */
type CellState = Exclude<SkillStatus, 'PENDING_REVIEW'> | 'NONE'

function getCell(row: SkillMatrixRow, categoryId: number): SkillMatrixCell | undefined {
  return row.cells[categoryId]
}

function getCellState(row: SkillMatrixRow, categoryId: number): CellState {
  const status = getCell(row, categoryId)?.status
  if (!status || status === 'PENDING_REVIEW') return 'NONE'
  return status
}

function getCellClass(state: CellState): string {
  switch (state) {
    case 'ACTIVE': return 'bg-green-100 dark:bg-green-900'
    case 'EXPIRED': return 'bg-red-100 dark:bg-red-900'
    default: return 'bg-surface-50 dark:bg-surface-900'
  }
}

function getCellIcon(state: CellState): string {
  switch (state) {
    case 'ACTIVE': return 'pi pi-check text-green-600 dark:text-green-400'
    case 'EXPIRED': return 'pi pi-times text-red-600 dark:text-red-400'
    default: return ''
  }
}

function getCellTooltip(row: SkillMatrixRow, categoryId: number): string {
  const state = getCellState(row, categoryId)
  const labels: Record<CellState, string> = {
    ACTIVE: '有効',
    EXPIRED: '期限切れ',
    NONE: '未登録',
  }
  return labels[state]
}

async function loadMatrix() {
  loading.value = true
  try {
    const res = await getSkillMatrix(props.teamId)
    matrixData.value = res.data
  } catch {
    notification.error('スキルマトリクスの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(loadMatrix)
defineExpose({ refresh: loadMatrix })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">スキルマトリクス</h2>
      <Button
        :aria-label="t('skill.matrix.refresh')"
        icon="pi pi-refresh"
        severity="secondary"
        text
        @click="loadMatrix"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <div v-else-if="!matrixData || categories.length === 0 || rows.length === 0" class="py-12 text-center">
      <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">マトリクスデータがありません</p>
    </div>

    <div v-else class="overflow-x-auto rounded-xl border border-surface-300 dark:border-surface-600">
      <table class="w-full min-w-[600px] text-sm">
        <thead>
          <tr class="bg-surface-100 dark:bg-surface-700">
            <th class="sticky left-0 z-10 bg-surface-100 px-4 py-3 text-left font-medium dark:bg-surface-700">
              メンバー
            </th>
            <th
              v-for="cat in categories"
              :key="cat.id"
              class="px-3 py-3 text-center font-medium"
            >
              <span class="whitespace-nowrap text-xs">{{ cat.name }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.userId"
            class="border-t border-surface-200 dark:border-surface-600"
          >
            <td class="sticky left-0 z-10 bg-surface-0 px-4 py-2 font-medium dark:bg-surface-800">
              {{ row.displayName }}
            </td>
            <td
              v-for="cat in categories"
              :key="cat.id"
              v-tooltip.top="getCellTooltip(row, cat.id)"
              :data-testid="`cell-${row.userId}-${cat.id}`"
              :data-status="getCellState(row, cat.id)"
              :class="getCellClass(getCellState(row, cat.id))"
              class="px-3 py-2 text-center"
            >
              <i v-if="getCellState(row, cat.id) !== 'NONE'" :class="getCellIcon(getCellState(row, cat.id))" />
              <span v-else class="text-surface-300">-</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="mt-3 flex flex-wrap gap-4 text-xs text-surface-500">
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded bg-green-100 dark:bg-green-900" />
        有効
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded bg-red-100 dark:bg-red-900" />
        期限切れ
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded bg-surface-50 dark:bg-surface-900" />
        未登録
      </span>
      <span class="text-surface-400">{{ t('skill.matrix.legendPendingNote') }}</span>
    </div>
  </div>
</template>
