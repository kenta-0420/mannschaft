<script setup lang="ts">
import type { KanbanStage, QuoteCard } from '~/types/repairPlanKanban'
import { STAGE_ORDER, TERMINAL_STAGES } from '~/types/repairPlanKanban'

const props = defineProps<{
  card: QuoteCard
  canEdit: boolean // ADMIN/DEPUTY_ADMIN のみ true
}>()

const emit = defineEmits<{
  move: [cardId: string, newStage: KanbanStage]
}>()

const { t } = useI18n()

const nextStage = computed((): KanbanStage | null => {
  const idx = STAGE_ORDER.indexOf(props.card.stage)
  if (idx < 0 || idx >= STAGE_ORDER.length - 1) return null
  return STAGE_ORDER[idx + 1] ?? null
})

const isTerminal = computed(() => TERMINAL_STAGES.includes(props.card.stage))

const vendorLabel = computed(() => {
  if (props.card.vendorNameSnapshot === null) {
    return t('repair_plan.kanban.card.vendor_hidden')
  }
  return props.card.vendorNameSnapshot
})

const amountDisplay = computed(() => {
  if (props.card.amount !== null) {
    return new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' }).format(
      props.card.amount,
    )
  }
  if (props.card.amountLabel !== null) {
    return props.card.amountLabel
  }
  return t('repair_plan.kanban.card.amount_hidden')
})

const stageColorClass = computed((): string => {
  const stageColorMap: Record<string, string> = {
    REQUESTED: 'bg-surface-200 text-surface-700',
    RECEIVED: 'bg-blue-100 text-blue-700',
    UNDER_REVIEW: 'bg-yellow-100 text-yellow-700',
    SHORTLISTED: 'bg-purple-100 text-purple-700',
    SELECTED: 'bg-green-100 text-green-700',
    REJECTED: 'bg-red-100 text-red-700',
  }
  return stageColorMap[props.card.stage] ?? 'bg-surface-100 text-surface-600'
})

function onAdvance() {
  if (nextStage.value) {
    emit('move', props.card.id, nextStage.value)
  }
}

function onReject() {
  emit('move', props.card.id, 'REJECTED')
}
</script>

<template>
  <div
    class="quote-kanban-card rounded-lg border border-surface-200 bg-white p-3 shadow-sm dark:border-surface-700 dark:bg-surface-800"
  >
    <!-- ヘッダ: ステージバッジ -->
    <div class="mb-2 flex items-center justify-between">
      <span
        class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
        :class="stageColorClass"
      >
        {{ $t(`repair_plan.kanban.stage.${card.stage.toLowerCase()}`) }}
      </span>
      <!-- 反社チェック期限切れバッジ -->
      <span
        v-if="card.complianceCheckStatus === 'EXPIRED'"
        class="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400"
      >
        <i class="pi pi-exclamation-triangle text-xs" />
        {{ $t('repair_plan.kanban.card.compliance_expired') }}
      </span>
    </div>

    <!-- 業者名 -->
    <div class="mb-1">
      <span class="text-xs text-surface-500 dark:text-surface-400">
        {{ $t('repair_plan.kanban.card.vendor') }}
      </span>
      <p
        class="text-sm font-medium text-surface-800 dark:text-surface-100"
        :class="{ 'italic text-surface-400': card.vendorNameSnapshot === null }"
      >
        {{ vendorLabel }}
      </p>
    </div>

    <!-- 見積金額 -->
    <div class="mb-3">
      <span class="text-xs text-surface-500 dark:text-surface-400">
        {{ $t('repair_plan.kanban.card.amount') }}
      </span>
      <p
        class="text-sm font-semibold text-surface-800 dark:text-surface-100"
        :class="{ 'italic text-surface-400': card.amount === null && card.amountLabel === null }"
      >
        {{ amountDisplay }}
      </p>
    </div>

    <!-- 操作ボタン（canEdit かつ非終端ステージのみ表示） -->
    <div v-if="canEdit && !isTerminal" class="flex gap-2">
      <!-- 次のステージへ進める -->
      <button
        v-if="nextStage"
        class="flex flex-1 items-center justify-center gap-1 rounded bg-primary-500 px-2 py-1 text-xs font-medium text-white transition hover:bg-primary-600"
        @click="onAdvance"
      >
        {{ $t(`repair_plan.kanban.stage.${nextStage.toLowerCase()}`) }}
        <i class="pi pi-chevron-right text-xs" />
      </button>
      <!-- 却下 -->
      <button
        class="flex items-center justify-center gap-1 rounded border border-red-300 px-2 py-1 text-xs font-medium text-red-600 transition hover:bg-red-50 dark:border-red-700 dark:text-red-400 dark:hover:bg-red-900/20"
        @click="onReject"
      >
        <i class="pi pi-times text-xs" />
        {{ $t('repair_plan.kanban.stage.rejected') }}
      </button>
    </div>
  </div>
</template>
