<script setup lang="ts">
import type { SkillMatrixResponse } from '~/types/skill'

const props = defineProps<{
  teamId: number
}>()

const { getSkillMatrix } = useSkillApi()
const notification = useNotification()

const matrixData = ref<SkillMatrixResponse | null>(null)
const loading = ref(false)

interface MatrixRow {
  userId: number
  displayName: string
  skills: Record<number, { status: string; name: string }>
}

const categories = computed<Array<{ id: number; name: string }>>(() => {
  if (!matrixData.value) return []
  return (matrixData.value as Record<string, unknown>).categories as Array<{ id: number; name: string }> ?? []
})

const rows = computed<MatrixRow[]>(() => {
  if (!matrixData.value) return []
  return (matrixData.value as Record<string, unknown>).members as MatrixRow[] ?? []
})

function getCellClass(status: string | undefined): string {
  if (!status) return 'bg-surface-50 dark:bg-surface-900'
  switch (status) {
    case 'ACTIVE': return 'bg-green-100 dark:bg-green-900'
    case 'EXPIRED': return 'bg-red-100 dark:bg-red-900'
    case 'PENDING_REVIEW': return 'bg-yellow-100 dark:bg-yellow-900'
    default: return 'bg-surface-50 dark:bg-surface-900'
  }
}

function getCellIcon(status: string | undefined): string {
  if (!status) return ''
  switch (status) {
    case 'ACTIVE': return 'pi pi-check text-green-600 dark:text-green-400'
    case 'EXPIRED': return 'pi pi-times text-red-600 dark:text-red-400'
    case 'PENDING_REVIEW': return 'pi pi-clock text-yellow-600 dark:text-yellow-400'
    default: return ''
  }
}

function getCellTooltip(row: MatrixRow, catId: number): string {
  const skill = row.skills[catId]
  if (!skill) return '未登録'
  const labels: Record<string, string> = {
    ACTIVE: '有効',
    EXPIRED: '期限切れ',
    PENDING_REVIEW: '確認待ち',
  }
  return `${skill.name} - ${labels[skill.status] || skill.status}`
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
        icon="pi pi-refresh"
        severity="secondary"
        text
        @click="loadMatrix"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
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
              :class="getCellClass(row.skills[cat.id]?.status)"
              class="px-3 py-2 text-center"
            >
              <i v-if="row.skills[cat.id]" :class="getCellIcon(row.skills[cat.id]?.status)" />
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
        <span class="inline-block h-3 w-3 rounded bg-yellow-100 dark:bg-yellow-900" />
        確認待ち
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded bg-surface-50 dark:bg-surface-900" />
        未登録
      </span>
    </div>
  </div>
</template>
