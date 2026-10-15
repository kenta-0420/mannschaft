<script setup lang="ts">
import type { KanbanStage, QuoteCard } from '~/types/repairPlanKanban'

defineProps<{
  stage: KanbanStage
  cards: QuoteCard[]
  canEdit: boolean
  stageLabel: string
}>()

defineEmits<{
  cardMove: [cardId: string, newStage: KanbanStage]
}>()
</script>

<template>
  <div
    class="kanban-stage flex min-h-40 w-48 shrink-0 flex-col rounded-lg bg-surface-100 dark:bg-surface-800"
  >
    <!-- ステージヘッダ -->
    <div
      class="flex items-center justify-between rounded-t-lg px-3 py-2 font-medium text-surface-700 dark:text-surface-200"
    >
      <span class="text-sm">{{ stageLabel }}</span>
      <span
        class="inline-flex h-5 w-5 items-center justify-center rounded-full bg-surface-300 text-xs dark:bg-surface-600"
      >
        {{ cards.length }}
      </span>
    </div>

    <!-- カード一覧 -->
    <div class="flex flex-1 flex-col gap-2 p-2">
      <QuoteKanbanCard
        v-for="card in cards"
        :key="card.id"
        :card="card"
        :can-edit="canEdit"
        @move="(cardId, newStage) => $emit('cardMove', cardId, newStage)"
      />
      <!-- カードなし -->
      <p
        v-if="cards.length === 0"
        class="py-4 text-center text-xs text-surface-400 dark:text-surface-500"
      >
        {{ $t('repair_plan.kanban.no_cards') }}
      </p>
    </div>
  </div>
</template>
