<script setup lang="ts">
/**
 * F12.5 Phase 2-E — Kanban ボード本体（6 カラム）。
 *
 * <p>vuedraggable で複数カラム間の DnD を実現し、ドロップ時に
 * {@code stage-changed} を emit して親側で API 呼び出しと楽観的更新を行う。</p>
 */
import draggable from 'vuedraggable'
import type {
  KanbanCard,
  KanbanColumn,
  KanbanStageKey,
  WorkflowStage,
} from '~/types/error-report'

const props = defineProps<{
  columns: KanbanColumn[]
  loading?: boolean
}>()

const emit = defineEmits<{
  /** カードがカラム間で移動した。新ステージは null（NULL カラム）または WorkflowStage。 */
  'stage-changed': [cardId: number, newStage: WorkflowStage | null]
  /** 「もっと表示」リンク押下。詳細画面へリダイレクト用。 */
  'load-more': [stageKey: KanbanStageKey]
  /** カードクリックで詳細を開く。 */
  open: [id: number]
}>()

const { t } = useI18n()

/** カラム key を WorkflowStage | null に変換する。 */
function stageKeyToWorkflowStage(key: KanbanStageKey): WorkflowStage | null {
  return key === 'NULL' ? null : key
}

/** カラムタイトル表示用の i18n キー。 */
function columnTitle(key: KanbanStageKey): string {
  return key === 'NULL' ? t('error_report.stage.null') : t(`error_report.stage.${key}`)
}

/**
 * vuedraggable の change イベント（{ added: { element, newIndex } } または
 * { removed: ... } または { moved: ... }）を受け取り、added 時にだけ API を呼ぶ。
 * removed は他カラムの added で処理されるため何もしない。
 */
interface DraggableChangeEvent {
  added?: { element: KanbanCard; newIndex: number }
  removed?: { element: KanbanCard; oldIndex: number }
  moved?: { element: KanbanCard; oldIndex: number; newIndex: number }
}

function onChange(column: KanbanColumn, evt: DraggableChangeEvent) {
  if (!evt.added) return
  const card = evt.added.element
  const newStage = stageKeyToWorkflowStage(column.stageKey)
  emit('stage-changed', card.id, newStage)
}

// vuedraggable は v-model で配列を双方向束縛するため、props を直接書き換えるのではなく
// 内部の reactive コピーで運用する。親側で楽観的更新する際は props を更新するため、
// watch で同期する。
const localColumns = ref<KanbanColumn[]>([])

watch(
  () => props.columns,
  (val) => {
    // 深いコピー（cards 配列の独立性を確保）
    localColumns.value = val.map((c) => ({ ...c, cards: [...c.cards] }))
  },
  { immediate: true, deep: true },
)
</script>

<template>
  <div class="overflow-x-auto pb-2">
    <div class="flex min-w-max gap-3">
      <section
        v-for="column in localColumns"
        :key="column.stageKey"
        class="flex w-72 shrink-0 flex-col rounded-xl border border-surface-200 bg-surface-50 dark:border-surface-700 dark:bg-surface-900"
      >
        <header
          class="flex items-center justify-between gap-2 border-b border-surface-200 px-3 py-2 dark:border-surface-700"
        >
          <h3 class="text-sm font-semibold">{{ columnTitle(column.stageKey) }}</h3>
          <span
            class="rounded-full bg-surface-200 px-2 py-0.5 font-mono text-xs text-surface-700 dark:bg-surface-700 dark:text-surface-300"
          >
            {{ column.totalCount }}
          </span>
        </header>

        <draggable
          v-model="column.cards"
          :group="{ name: 'kanban', pull: true, put: true }"
          :sort="false"
          :animation="200"
          :disabled="loading"
          item-key="id"
          ghost-class="opacity-50"
          class="flex min-h-[120px] flex-1 flex-col gap-2 p-2"
          @change="(evt: DraggableChangeEvent) => onChange(column, evt)"
        >
          <template #item="{ element }: { element: KanbanCard }">
            <ErrorReportKanbanCard :card="element" @open="(id) => emit('open', id)" />
          </template>
        </draggable>

        <div
          v-if="column.cards.length === 0"
          class="px-3 pb-2 text-center text-xs text-surface-400"
        >
          {{ t('error_report.kanban.empty_column') }}
        </div>

        <footer
          v-if="column.hasMore"
          class="border-t border-surface-200 px-3 py-2 text-center dark:border-surface-700"
        >
          <Button
            :label="t('error_report.kanban.load_more')"
            size="small"
            text
            @click="emit('load-more', column.stageKey)"
          />
        </footer>
      </section>
    </div>
  </div>
</template>
