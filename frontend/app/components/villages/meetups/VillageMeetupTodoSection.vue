<script setup lang="ts">
/**
 * 寄合詳細 — 宿題TODOセクション（F17.2 Wave1 ②寄合後半戦 §4.3/§4.4）。
 *
 * ADHD-UX 方針（入力摩擦ゼロ）に沿い、1行入力＋チェック完了の最軽量 UI とする。
 *
 * - 作成: 幹事＋村長/長老のみ（`canManage`）
 * - 手を挙げる（claim）: 未割当 TODO に村人本人が押せる
 * - 手放す（release）: 割当済み TODO の担当者本人のみ表示
 * - 完了（complete）: 担当者本人＋幹事のみ表示
 * - CANCELLED では書込み UI を出さない（`canWrite` が false のとき操作ボタンを隠す・§4.5）
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import type { VillageMeetupTodoResponse } from '~/types/village'

const props = defineProps<{
  todos: VillageMeetupTodoResponse[]
  currentUserId: number | null
  /** 幹事＋村長/長老か（TODO 作成の可否） */
  canManage: boolean
  /** 書込み可能な寄合状態か（CONFIRMED のみ・§4.5） */
  canWrite: boolean
  loading: boolean
  creating: boolean
}>()

const emit = defineEmits<{
  create: [title: string]
  claim: [todoId: string]
  complete: [todoId: string]
  release: [todoId: string]
}>()

const { t } = useI18n()
const draftTitle = ref('')

function isMine(todo: VillageMeetupTodoResponse): boolean {
  return props.currentUserId !== null && todo.assigneeUserId === props.currentUserId
}

function canComplete(todo: VillageMeetupTodoResponse): boolean {
  return props.canWrite && !todo.doneAt && (isMine(todo) || props.canManage)
}

function submitCreate() {
  const title = draftTitle.value.trim()
  if (!title) return
  emit('create', title)
  draftTitle.value = ''
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h3 class="font-semibold">
      {{ t('village.meetup.todo.title') }}
    </h3>

    <div v-if="loading" class="text-center py-3 text-surface-500">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="todos.length === 0" class="text-xs text-surface-500">
      {{ t('village.meetup.todo.empty') }}
    </div>
    <div v-else class="flex flex-col gap-1">
      <div
        v-for="todo in todos"
        :key="todo.id"
        class="flex items-center gap-2 rounded border border-surface-200 px-2 py-1.5 text-sm dark:border-surface-700"
      >
        <Checkbox
          :model-value="!!todo.doneAt"
          binary
          :disabled="!canComplete(todo)"
          :aria-label="t('village.meetup.todo.complete')"
          @update:model-value="() => canComplete(todo) && emit('complete', todo.id)"
        />
        <span class="flex-1 min-w-0 truncate" :class="todo.doneAt ? 'line-through text-surface-400' : ''">
          {{ todo.title }}
        </span>
        <span class="text-xs text-surface-500 whitespace-nowrap">
          {{ todo.assigneeDisplayName ?? t('village.meetup.todo.unassigned') }}
        </span>
        <Button
          v-if="canWrite && !todo.assigneeUserId && !todo.doneAt"
          :label="t('village.meetup.todo.claim')"
          size="small"
          text
          @click="emit('claim', todo.id)"
        />
        <Button
          v-if="canWrite && isMine(todo) && !todo.doneAt"
          :label="t('village.meetup.todo.release')"
          size="small"
          severity="secondary"
          text
          @click="emit('release', todo.id)"
        />
      </div>
    </div>

    <div v-if="canManage && canWrite" class="flex items-center gap-2 mt-1">
      <InputText
        v-model="draftTitle"
        class="w-full"
        :placeholder="t('village.meetup.todo.titlePlaceholder')"
        @keyup.enter="submitCreate"
      />
      <Button
        :label="t('village.meetup.todo.add')"
        icon="pi pi-plus"
        size="small"
        :disabled="!draftTitle.trim() || creating"
        :loading="creating"
        @click="submitCreate"
      />
    </div>
  </div>
</template>
