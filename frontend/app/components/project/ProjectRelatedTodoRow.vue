<script setup lang="ts">
import type { TodoResponse } from '~/types/todo'
import { normalizeProjectRelatedTodo } from '~/utils/projectRelatedTodo'

/**
 * プロジェクト詳細の「関連タスク」1行。
 * タイトル・状態・期限・担当者を出し、todoHref があれば TODO 詳細へのリンクにする。
 * 390px 幅でも横にはみ出さないよう、行内は折り返し（flex-wrap / break-words / min-w-0）にする。
 */
const props = defineProps<{
  todo: TodoResponse
  /** TODO 詳細ページへのパス。個人スコープのように詳細画面が無い場合は省略（リンクなし）。 */
  todoHref?: string
}>()

const view = computed(() => normalizeProjectRelatedTodo(props.todo))
</script>

<template>
  <div
    class="min-w-0 rounded-lg border border-surface-100 p-3 dark:border-surface-600"
    data-testid="project-related-todo-row"
  >
    <div class="flex min-w-0 flex-wrap items-center gap-2">
      <NuxtLink
        v-if="todoHref"
        :to="todoHref"
        class="min-w-0 break-words text-sm font-medium text-primary hover:underline"
        :class="view.isCompleted ? 'line-through opacity-70' : ''"
        :title="$t('project.related_todo_open')"
      >
        {{ view.title }}
      </NuxtLink>
      <span
        v-else
        class="min-w-0 break-words text-sm font-medium"
        :class="view.isCompleted ? 'line-through opacity-70' : ''"
      >
        {{ view.title }}
      </span>
      <TodoStatusLabelBadge :label="view.statusLabel" :fallback-bucket="view.statusBucket" />
    </div>
    <div class="mt-1 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-500">
      <span class="inline-flex items-center gap-1">
        <i class="pi pi-calendar" />
        <span>{{ view.dueDate ?? $t('project.related_todo_no_due') }}</span>
      </span>
      <span class="inline-flex min-w-0 items-center gap-1">
        <i class="pi pi-user" />
        <span class="min-w-0 break-words">{{ view.assigneeNames || $t('project.related_todo_no_assignee') }}</span>
      </span>
    </div>
  </div>
</template>
