<script setup lang="ts">
import type { MyTodo, ListGroup } from '~/composables/useTodoList'
import { priorityBorder, priorityLabel, priorityClass } from '~/composables/useTodoList'

defineProps<{
  listGroups: ListGroup[]
  scopeDisplayName: (todo: MyTodo) => string
  scopeColor: (scopeType: string) => string
  formatDate: (d: string | null) => string
  isOverdue: (todo: MyTodo) => boolean
  nextStatus: (current: string) => string
  nextStatusLabel: (current: string) => string
}>()

const emit = defineEmits<{
  changeStatus: [todo: MyTodo, status: string]
}>()
</script>

<template>
  <div>
    <div v-if="listGroups.length === 0" class="py-16 text-center text-surface-400">
      <i class="pi pi-check-circle mb-3 text-4xl text-green-400" />
      <p>TODOはすべて完了しています</p>
    </div>

    <div v-for="group in listGroups" :key="group.key" class="mb-6">
      <div class="mb-2 flex items-center gap-2">
        <i :class="[group.icon, group.color, 'text-sm']" />
        <span :class="[group.color, 'text-sm font-semibold']">{{ group.label }}</span>
        <span
          class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700"
        >
          {{ group.todos.length }}
        </span>
      </div>

      <div class="space-y-2">
        <div
          v-for="todo in group.todos"
          :key="todo.id"
          class="flex items-center gap-3 rounded-xl border-2 border-surface-400 bg-surface-0 px-4 py-3 transition-shadow hover:shadow-sm dark:border-surface-500 dark:bg-surface-800"
          :class="priorityBorder[todo.priority]"
        >
          <Checkbox
            :model-value="todo.status === 'COMPLETED'"
            binary
            @update:model-value="
              emit('changeStatus', todo, todo.status === 'COMPLETED' ? 'OPEN' : 'COMPLETED')
            "
          />

          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <p
                class="text-sm font-medium"
                :class="
                  todo.status === 'COMPLETED'
                    ? 'text-surface-400 line-through'
                    : 'text-surface-800 dark:text-surface-100'
                "
              >
                {{ todo.title }}
              </p>
              <span
                class="rounded-full px-2 py-0.5 text-[11px] font-medium"
                :class="scopeColor(todo.scopeType)"
              >
                {{ scopeDisplayName(todo) }}
              </span>
            </div>
            <div class="mt-1 flex items-center gap-3">
              <span
                v-if="todo.dueDate"
                class="text-xs"
                :class="isOverdue(todo) ? 'font-semibold text-red-500' : 'text-surface-400'"
              >
                <i class="pi pi-calendar mr-0.5" />{{ formatDate(todo.dueDate) }}
                <span v-if="isOverdue(todo)">（期限切れ）</span>
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

          <Button
            v-if="todo.status !== 'COMPLETED'"
            :label="nextStatusLabel(todo.status)"
            size="small"
            text
            severity="secondary"
            class="shrink-0 !text-xs"
            @click="emit('changeStatus', todo, nextStatus(todo.status))"
          />
        </div>
      </div>
    </div>
  </div>
</template>
