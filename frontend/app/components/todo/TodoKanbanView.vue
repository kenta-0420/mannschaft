<script setup lang="ts">
import type { MyTodo, KanbanCol } from '~/composables/useTodoList'
import { priorityBorder, priorityLabel, priorityClass } from '~/composables/useTodoList'

defineProps<{
  kanbanCols: KanbanCol[]
  scopeDisplayName: (todo: MyTodo) => string
  scopeColor: (scopeType: string) => string
  formatDate: (d: string | null) => string
  isOverdue: (todo: MyTodo) => boolean
}>()

const emit = defineEmits<{
  changeStatus: [todo: MyTodo, status: string]
  create: []
}>()
</script>

<template>
  <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
    <div
      v-for="col in kanbanCols"
      :key="col.status"
      class="rounded-xl border border-surface-300 dark:border-surface-600"
    >
      <div class="flex items-center justify-between rounded-t-xl px-4 py-3" :class="col.color">
        <span class="font-semibold" :class="col.headerColor">{{ col.label }}</span>
        <span
          class="rounded-full bg-white/60 px-2 py-0.5 text-xs font-bold dark:bg-black/20"
          :class="col.headerColor"
        >
          {{ col.todos.length }}
        </span>
      </div>

      <div class="space-y-2 p-3">
        <div
          v-for="todo in col.todos"
          :key="todo.id"
          class="rounded-lg border border-surface-300 bg-surface-0 p-3 shadow-sm dark:border-surface-600 dark:bg-surface-800"
          :class="priorityBorder[todo.priority]"
        >
          <p
            class="mb-2 text-sm font-medium leading-snug text-surface-800 dark:text-surface-100"
            :class="{ 'line-through text-surface-400': todo.status === 'COMPLETED' }"
          >
            {{ todo.title }}
          </p>

          <div class="flex flex-wrap items-center gap-1.5">
            <span
              class="rounded-full px-1.5 py-0.5 text-[10px] font-medium"
              :class="scopeColor(todo.scopeType)"
            >
              {{ scopeDisplayName(todo) }}
            </span>
            <span
              class="rounded-full px-1.5 py-0.5 text-[10px] font-semibold"
              :class="priorityClass[todo.priority]"
            >
              {{ priorityLabel[todo.priority] }}
            </span>
            <span
              v-if="todo.dueDate"
              class="flex items-center gap-0.5 text-[10px]"
              :class="isOverdue(todo) ? 'text-red-500 font-semibold' : 'text-surface-400'"
            >
              <i class="pi pi-calendar" />{{ formatDate(todo.dueDate) }}
            </span>
          </div>

          <div v-if="todo.assignees.length > 0" class="mt-2 flex -space-x-1">
            <Avatar
              v-for="a in todo.assignees.slice(0, 4)"
              :key="a.userId"
              v-tooltip="a.displayName"
              :image="a.avatarUrl ?? undefined"
              :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
              size="small"
              shape="circle"
              class="border-2 border-surface-0 dark:border-surface-800"
            />
          </div>

          <div class="mt-2 flex gap-1">
            <Button
              v-if="col.status !== 'OPEN'"
              v-tooltip="col.status === 'IN_PROGRESS' ? '未着手に戻す' : '進行中に戻す'"
              icon="pi pi-arrow-left"
              size="small"
              text
              severity="secondary"
              class="!p-1"
              @click="emit('changeStatus', todo, col.status === 'IN_PROGRESS' ? 'OPEN' : 'IN_PROGRESS')"
            />
            <Button
              v-if="col.status !== 'COMPLETED'"
              v-tooltip="col.status === 'OPEN' ? '進行中にする' : '完了にする'"
              icon="pi pi-arrow-right"
              size="small"
              text
              severity="secondary"
              class="!p-1 ml-auto"
              @click="emit('changeStatus', todo, col.status === 'OPEN' ? 'IN_PROGRESS' : 'COMPLETED')"
            />
          </div>
        </div>

        <div v-if="col.todos.length === 0" class="py-6 text-center text-xs text-surface-400">
          なし
        </div>

        <button
          v-if="col.status === 'OPEN'"
          class="flex w-full items-center gap-2 rounded-lg border border-dashed border-surface-300 px-3 py-2 text-sm text-surface-400 transition-colors hover:border-primary hover:text-primary dark:border-surface-600"
          @click="emit('create')"
        >
          <i class="pi pi-plus" />追加する
        </button>
      </div>
    </div>
  </div>
</template>
