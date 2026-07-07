<script setup lang="ts">
import type { MyTodo, ListGroup } from '~/composables/useTodoList'
import { priorityBorder, priorityLabel, priorityClass } from '~/composables/useTodoList'
import type { TodoStatusLabel, TodoStatusLabelInfo } from '~/types/todoStatusLabel'

/**
 * F02.3.1 + マイTODO UX 改善:
 *
 * - 行全体クリックで詳細ページへ遷移（行ラッパは div + role=link、Enter/Space でも遷移）。
 *   `<a>` 内に `<button>`/`<input>` を入れる HTML 仕様違反を避けるため、`<NuxtLink>` ではなく
 *   `useRouter().push()` を呼ぶ div ラッパとして実装する。
 * - 行頭にチェックボックスを配置: COMPLETED トグル（OPEN <-> COMPLETED）。
 * - 行末ステータスラベルバッジクリックで Popover 表示 → 任意ラベルを直接選択して変更可。
 * - クリック競合は内側コントロールへの @click.stop で個別に処理。
 *
 * 設計書 §10 「マイ TODO 一覧 UX 補強」を参照。
 * 一覧での運用効率を優先し、詳細画面の Select+変更ボタン UI と二段構えで提供する。
 */
const props = defineProps<{
  listGroups: ListGroup[]
  scopeDisplayName: (todo: MyTodo) => string
  scopeColor: (scopeType: string) => string
  formatDate: (d: string | null) => string
  isOverdue: (todo: MyTodo) => boolean
}>()

const emit = defineEmits<{
  /**
   * ステータス変更要求。bucket 指定の場合は SYSTEM 既定ラベルへフォールバック更新。
   * statusLabelId 指定の場合はそのカスタムラベルへ直接更新。
   */
  'change-status': [
    todo: MyTodo,
    payload: { status?: string; statusLabelId?: number },
  ]
  /** TODO 削除要求 */
  'delete-todo': [todo: MyTodo]
}>()

const { t } = useI18n()
const labelApi = useTodoStatusLabelApi()
const router = useRouter()

function todoLink(todo: MyTodo): string {
  if (todo.scopeType === 'TEAM' && todo.scopeSlug) {
    return `/teams/${todo.scopeSlug}/todos/${todo.id}`
  }
  if (todo.scopeType === 'ORGANIZATION' && todo.scopeSlug) {
    return `/organizations/${todo.scopeSlug}/todos/${todo.id}`
  }
  // PERSONAL または scopeSlug が未設定（slug 移行前の古いデータの保険）
  return `/todos/${todo.id}`
}

function navigateTo(todo: MyTodo) {
  router.push(todoLink(todo))
}

// === チェックボックス（完了トグル） =========================================

function isCompleted(todo: MyTodo): boolean {
  // 判定は todo.status のみで行う。
  // statusLabel.bucket は status から導出される（B-6 修正により
  // status_label_id NULL 時はサーバ側で SYSTEM 既定にフォールバック）ため、
  // bucket を優先すると楽観更新（status='OPEN'）と statusLabel（bucket='COMPLETED'）が
  // 一時的に食い違ったときに UI が COMPLETED に見えるバグが発生する。
  // 単一の真実源として status のみを参照することで楽観更新の整合性を保つ。
  return todo.status === 'COMPLETED'
}

function onToggleComplete(todo: MyTodo, ev: Event) {
  // NuxtLink への伝播を抑止
  ev.stopPropagation()
  ev.preventDefault()
  const next = isCompleted(todo) ? 'OPEN' : 'COMPLETED'
  emit('change-status', todo, { status: next })
}

// === ラベル変更 Popover ====================================================

const popover = ref<{ show: (ev: Event) => void; hide: () => void } | null>(null)
const popoverTodo = ref<MyTodo | null>(null)
const popoverLabels = ref<TodoStatusLabel[]>([])
const popoverLoading = ref(false)

async function openLabelPopover(todo: MyTodo, ev: Event) {
  ev.stopPropagation()
  ev.preventDefault()
  popoverTodo.value = todo
  popoverLabels.value = []
  popoverLoading.value = true
  popover.value?.show(ev)
  try {
    const scope =
      todo.scopeType === 'PERSONAL'
        ? 'me'
        : todo.scopeType === 'TEAM'
          ? 'team'
          : 'organization'
    const res = await labelApi.listLabels(
      scope,
      todo.scopeType === 'PERSONAL' ? undefined : todo.scopeId ?? undefined,
    )
    popoverLabels.value = res.data
  } finally {
    popoverLoading.value = false
  }
}

function selectLabel(label: TodoStatusLabel) {
  const todo = popoverTodo.value
  if (!todo) return
  emit('change-status', todo, { statusLabelId: label.id })
  popover.value?.hide()
  popoverTodo.value = null
}

function isCurrentLabel(label: TodoStatusLabel): boolean {
  const cur = popoverTodo.value?.statusLabel
  if (!cur) return false
  return cur.id === label.id
}

function colorOfLabel(label: TodoStatusLabel | TodoStatusLabelInfo): string {
  if (label.color) return label.color
  // SYSTEM 既定の代表色（TodoStatusLabelBadge と揃える）
  if (label.bucket === 'OPEN') return '#94a3b8'
  if (label.bucket === 'IN_PROGRESS') return '#3b82f6'
  return '#22c55e'
}

// === TODO削除 ================================================================

// ADHD 配慮 AC-16: 確認ダイアログを廃止し、即時削除 + Undo Toast に置換する。
// Undo Toast の発行と restore は useTodoList.deleteTodo が担当する。
function confirmDelete(todo: MyTodo, ev: Event) {
  ev.stopPropagation()
  emit('delete-todo', todo)
}

// 未使用警告抑止
void props
</script>

<template>
  <div>
    <div v-if="listGroups.length === 0" class="py-16 text-center text-surface-400">
      <i class="pi pi-check-circle mb-3 text-4xl text-green-400" />
      <p>{{ t('todo.list.allDone') }}</p>
    </div>

    <div
      class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
    >
    <div
      v-for="group in listGroups"
      :key="group.key"
      class="field-bordered overflow-hidden rounded-xl"
    >
      <div class="flex items-center justify-between px-4 py-3" :class="group.headerBg">
        <div class="flex items-center gap-2">
          <i :class="[group.icon, group.color, 'text-sm']" />
          <span :class="[group.color, 'text-sm font-semibold']">{{ group.label }}</span>
        </div>
        <span
          class="rounded-full bg-white/60 px-2 py-0.5 text-xs font-bold dark:bg-black/20"
          :class="group.color"
        >
          {{ group.todos.length }}
        </span>
      </div>

      <div class="space-y-2 p-3">
        <div
          v-for="todo in group.todos"
          :key="todo.id"
          role="link"
          tabindex="0"
          :aria-label="todo.content?.title ?? ''"
          class="group block cursor-pointer rounded-xl border-2 border-surface-400 bg-surface-0 transition-shadow hover:shadow-md focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-500 dark:bg-surface-800"
          :class="priorityBorder[todo.priority]"
          @click="navigateTo(todo)"
          @keydown.enter="navigateTo(todo)"
          @keydown.space.prevent="navigateTo(todo)"
        >
          <div class="flex items-center gap-3 px-4 py-3">
            <!-- 完了トグル チェックボックス -->
            <label
              class="flex shrink-0 cursor-pointer items-center"
              :title="t('todo.list.toggleCompleteHint')"
              @click.stop
            >
              <input
                type="checkbox"
                class="h-5 w-5 cursor-pointer accent-primary"
                :checked="isCompleted(todo)"
                :aria-label="t('todo.list.toggleCompleteAria')"
                @click="onToggleComplete(todo, $event)"
              >
            </label>

            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span
                  class="text-sm font-medium"
                  :class="
                    isCompleted(todo)
                      ? 'text-surface-400 line-through'
                      : 'text-surface-800 dark:text-surface-100'
                  "
                >
                  {{ todo.content?.title }}
                </span>
                <span
                  class="rounded-full px-2 py-0.5 text-[11px] font-medium"
                  :class="scopeColor(todo.scopeType)"
                >
                  {{ scopeDisplayName(todo) }}
                </span>
                <!-- ラベルバッジ: クリックで Popover を開いて直接変更 -->
                <button
                  type="button"
                  class="rounded-full focus:outline-none focus:ring-2 focus:ring-primary"
                  :title="t('todo.list.changeLabelHint')"
                  :aria-label="t('todo.list.changeLabelAria')"
                  @click="openLabelPopover(todo, $event)"
                >
                  <TodoStatusLabelBadge
                    :label="todo.statusLabel"
                    :fallback-bucket="todo.status"
                  />
                </button>
              </div>
              <div class="mt-1 flex items-center gap-3">
                <span v-if="todo.startDate || todo.dueDate" class="text-xs text-surface-400">
                  <i class="pi pi-calendar mr-0.5" />
                  <span v-if="todo.startDate">{{ formatDate(todo.startDate) }}</span>
                  <span v-if="todo.startDate && todo.dueDate"> 〜 </span>
                  <span
                    v-if="todo.dueDate"
                    :class="isOverdue(todo) ? 'font-semibold text-red-500' : ''"
                  >{{ formatDate(todo.dueDate)
                  }}<span v-if="isOverdue(todo)">{{ t('todo.list.overdueSuffix') }}</span></span>
                </span>
                <span
                  v-if="todo.assignees.length > 0"
                  class="flex items-center gap-1 text-xs text-surface-400"
                >
                  <i class="pi pi-user" />
                  {{ todo.assignees.map((a) => a.displayName).join(', ') }}
                </span>
              </div>
            </div>

            <span
              class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
              :class="priorityClass[todo.priority]"
            >
              {{ priorityLabel[todo.priority] }}
            </span>
            <!-- 削除ボタン -->
            <button
              type="button"
              class="ml-1 shrink-0 rounded p-1 text-surface-300 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/30 focus:opacity-100 focus:outline-none"
              :title="t('todo.list.deleteButton')"
              :aria-label="t('todo.list.deleteAriaLabel')"
              @click.stop="confirmDelete(todo, $event)"
            >
              <i class="pi pi-trash text-xs" />
            </button>
          </div>
        </div>
      </div>
    </div>
    </div>

    <!-- ラベル変更 Popover（一覧共通で 1 つ） -->
    <Popover ref="popover">
      <div class="flex flex-col gap-1 py-1" style="min-width: 220px">
        <div class="px-2 pb-1 text-xs font-semibold text-surface-500">
          {{ t('todo.list.popoverTitle') }}
        </div>
        <div v-if="popoverLoading" class="px-2 py-2 text-xs text-surface-400">
          {{ t('todo.list.popoverLoading') }}
        </div>
        <div v-else-if="popoverLabels.length === 0" class="px-2 py-2 text-xs text-surface-400">
          {{ t('todo.list.popoverEmpty') }}
        </div>
        <button
          v-for="label in popoverLabels"
          v-else
          :key="label.id"
          type="button"
          class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
          :class="isCurrentLabel(label) ? 'bg-surface-100 dark:bg-surface-700' : ''"
          @click="selectLabel(label)"
        >
          <span
            class="inline-block h-2.5 w-2.5 rounded-full"
            :style="{ backgroundColor: colorOfLabel(label) }"
          />
          <span class="flex-1">{{ label.name }}</span>
          <i v-if="isCurrentLabel(label)" class="pi pi-check text-xs text-primary" />
        </button>
      </div>
    </Popover>
  </div>
</template>
