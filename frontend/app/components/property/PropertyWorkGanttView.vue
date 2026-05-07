<script setup lang="ts">
/**
 * 物件履歴台帳 ガントチャートビュー（F09.13 Phase 2-α-4）。
 *
 * frappe-gantt v1.2.x を用いて、PropertyWorkPackageSummaryResponse の配列を
 * ガントチャートとして描画する。SSR 不可ライブラリのため必ず親側で
 * `<ClientOnly>` でラップして使用すること。
 *
 * - 開始/終了日: plannedStartDate / plannedEndDate を使用
 *   - 計画日が片側 or 両方欠落のパッケージはガント描画対象から除外し、
 *     description 欄に件数を表示する
 * - 進捗率: status から推定（PLANNED=0% / IN_PROGRESS=50% / COMPLETED|CLOSED=100% / CANCELLED=0%）
 * - クリック: emit('select', packageId) で親に伝搬
 * - 日付範囲はビューモード切替（Day / Week / Month）で内部追従
 */
import type { GanttTask } from 'frappe-gantt'
import type { PropertyWorkPackageSummaryResponse, WorkPackageStatus } from '~/types/property'

interface Props {
  packages: PropertyWorkPackageSummaryResponse[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  select: [packageId: number]
}>()

const { t } = useI18n()

const containerRef = ref<HTMLDivElement | null>(null)
const ganttInstance = ref<unknown>(null)

/** status から進捗率(0..100)を推定する。 */
function statusToProgress(status: WorkPackageStatus): number {
  switch (status) {
    case 'PLANNED':
      return 0
    case 'IN_PROGRESS':
      return 50
    case 'COMPLETED':
    case 'CLOSED':
      return 100
    case 'CANCELLED':
      return 0
    default:
      return 0
  }
}

/** status を CSS クラス名に変換し、frappe-gantt の bar に色を付ける。 */
function statusClass(status: WorkPackageStatus): string {
  return `bar-status-${status.toLowerCase()}`
}

/** ガント描画対象のタスク配列。計画日が両方揃っているパッケージのみ含める。 */
const tasks = computed<GanttTask[]>(() =>
  props.packages
    .filter((p) => p.plannedStartDate && p.plannedEndDate)
    .map((p) => ({
      id: String(p.id),
      name: p.title,
      start: p.plannedStartDate as string,
      end: p.plannedEndDate as string,
      progress: statusToProgress(p.status),
      custom_class: statusClass(p.status),
    })),
)

/** 計画日欠落のため除外された件数。注意書き表示用。 */
const excludedCount = computed(
  () => props.packages.filter((p) => !p.plannedStartDate || !p.plannedEndDate).length,
)

async function destroyGantt() {
  // frappe-gantt は明示的な destroy API がないため、コンテナの DOM を空にする
  if (containerRef.value) {
    containerRef.value.innerHTML = ''
  }
  ganttInstance.value = null
}

async function renderGantt() {
  if (!containerRef.value) return
  await destroyGantt()
  if (tasks.value.length === 0) return

  // 動的 import で SSR 評価を確実に避ける
  const mod = await import('frappe-gantt')
  // CSS も同様に動的 import（クライアント評価のみ）
  await import('frappe-gantt/dist/frappe-gantt.css')
  const GanttCtor = mod.default

  ganttInstance.value = new GanttCtor(containerRef.value, tasks.value, {
    view_mode: 'Week',
    language: 'ja',
    bar_height: 24,
    padding: 18,
    on_click: (task) => {
      const id = Number(task.id)
      if (Number.isFinite(id)) emit('select', id)
    },
  })
}

watch(
  () => props.packages,
  () => {
    void renderGantt()
  },
  { deep: true },
)

onMounted(() => {
  void renderGantt()
})

onBeforeUnmount(() => {
  void destroyGantt()
})
</script>

<template>
  <div class="property-gantt-wrapper" data-testid="property-gantt-view">
    <div
      v-if="tasks.length === 0"
      class="rounded-md border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
    >
      {{ t('property.gantt.empty') }}
    </div>
    <div v-else class="space-y-2">
      <p
        v-if="excludedCount > 0"
        class="text-xs text-surface-500 dark:text-surface-400"
        data-testid="property-gantt-excluded-note"
      >
        {{ t('property.gantt.excludedNote', { count: excludedCount }) }}
      </p>
      <div
        ref="containerRef"
        class="property-gantt-container overflow-x-auto rounded-md border border-surface-200 bg-white p-2 dark:border-surface-700 dark:bg-surface-900"
      />
    </div>
  </div>
</template>

<style scoped>
.property-gantt-container {
  min-height: 200px;
}

/* status ごとのバー配色（frappe-gantt の bar クラスに上乗せ） */
.property-gantt-container :deep(.bar-status-planned .bar) {
  fill: #93c5fd; /* blue-300 */
}
.property-gantt-container :deep(.bar-status-in_progress .bar) {
  fill: #fde68a; /* amber-200 */
}
.property-gantt-container :deep(.bar-status-completed .bar) {
  fill: #86efac; /* green-300 */
}
.property-gantt-container :deep(.bar-status-closed .bar) {
  fill: #cbd5e1; /* slate-300 */
}
.property-gantt-container :deep(.bar-status-cancelled .bar) {
  fill: #fca5a5; /* red-300 */
}
</style>
