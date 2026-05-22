<script setup lang="ts">
import type { KanbanStage, QuoteKanban } from '~/types/repairPlanKanban'
import { ALL_STAGES, STAGE_ORDER } from '~/types/repairPlanKanban'

const props = defineProps<{
  kanban: QuoteKanban
  canEdit: boolean
}>()

const emit = defineEmits<{
  cardMoved: [cardId: string, newStage: KanbanStage]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

// stage → cards のグルーピング
const cardsByStage = computed(() => {
  const map = {} as Record<KanbanStage, QuoteKanban['cards']>
  for (const stage of ALL_STAGES) {
    map[stage] = props.kanban.cards
      .filter((c) => c.stage === stage)
      .sort((a, b) => a.displayOrder - b.displayOrder)
  }
  return map
})

// 締切日時のフォーマット
const deadlineLabel = computed(() => formatDateTime(props.kanban.bidDeadlineAt))

const isPastDeadline = computed(() => new Date() > new Date(props.kanban.bidDeadlineAt))

const statusBadgeClass = computed(() => {
  const statusMap: Record<string, string> = {
    OPEN: 'bg-green-100 text-green-700',
    CLOSED: 'bg-surface-200 text-surface-600',
    AWARDED: 'bg-blue-100 text-blue-700',
    CANCELED: 'bg-red-100 text-red-700',
  }
  return statusMap[props.kanban.status] ?? 'bg-surface-100 text-surface-600'
})

// ステージ配列（型キャストなしで使えるようにする）
const allStages: KanbanStage[] = [...STAGE_ORDER, 'REJECTED']
</script>

<template>
  <div class="quote-kanban-board">
    <!-- ボードヘッダ -->
    <div
      class="mb-4 flex flex-wrap items-start justify-between gap-3 rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900"
    >
      <div>
        <h2 class="text-lg font-semibold text-surface-800 dark:text-surface-100">
          {{ kanban.title }}
        </h2>
        <div
          class="mt-1 flex flex-wrap items-center gap-3 text-sm text-surface-500 dark:text-surface-400"
        >
          <!-- 締切日時 -->
          <span class="flex items-center gap-1">
            <i class="pi pi-clock" />
            {{ $t('repair_plan.kanban.deadline.label') }}: {{ deadlineLabel }}
          </span>
          <!-- 締切済みバッジ -->
          <span
            v-if="isPastDeadline"
            class="inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
          >
            {{ $t('repair_plan.kanban.deadline.past') }}
          </span>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <!-- ステータスバッジ -->
        <span
          class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium"
          :class="statusBadgeClass"
        >
          {{ $t(`repair_plan.kanban.status.${kanban.status.toLowerCase()}`) }}
        </span>
        <!-- 可視性バッジ -->
        <span
          class="inline-flex items-center gap-1 rounded-full bg-surface-100 px-2.5 py-0.5 text-xs font-medium text-surface-600 dark:bg-surface-700 dark:text-surface-300"
        >
          <i class="pi pi-eye text-xs" />
          {{ $t(`repair_plan.kanban.visibility.${kanban.visibilityToMember.toLowerCase()}`) }}
        </span>
      </div>
    </div>

    <!-- カンバンボード（横スクロール） -->
    <div class="overflow-x-auto pb-4">
      <div class="flex gap-3" style="min-width: max-content">
        <KanbanStage
          v-for="stage in allStages"
          :key="stage"
          :stage="stage"
          :cards="cardsByStage[stage] ?? []"
          :can-edit="canEdit"
          :stage-label="t(`repair_plan.kanban.stage.${stage.toLowerCase()}`)"
          @card-move="(cardId, newStage) => emit('cardMoved', cardId, newStage)"
        />
      </div>
    </div>
  </div>
</template>
