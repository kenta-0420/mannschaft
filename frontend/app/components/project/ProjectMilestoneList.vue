<script setup lang="ts">
import type { MilestoneResponse } from '~/types/project'

defineProps<{
  milestones: MilestoneResponse[]
  canEdit: boolean
}>()

const emit = defineEmits<{
  create: []
  edit: [ms: MilestoneResponse]
  toggleComplete: [ms: MilestoneResponse]
  remove: [ms: MilestoneResponse]
}>()
</script>

<template>
  <div class="mb-6">
    <div class="mb-2 flex items-center justify-between">
      <h2 class="text-lg font-semibold">マイルストーン</h2>
      <Button
        v-if="canEdit"
        icon="pi pi-plus"
        label="追加"
        text
        size="small"
        @click="emit('create')"
      />
    </div>
    <div class="flex flex-col gap-2">
      <div
        v-for="ms in milestones"
        :key="ms.id"
        class="flex items-center justify-between rounded-xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="flex items-center gap-3">
          <Checkbox
            :model-value="ms.completed"
            :binary="true"
            @update:model-value="emit('toggleComplete', ms)"
          />
          <div>
            <p :class="ms.completed ? 'text-surface-400 line-through' : 'font-medium'">
              {{ ms.title }}
            </p>
            <p v-if="ms.dueDate" class="text-xs text-surface-500">期限: {{ ms.dueDate }}</p>
          </div>
        </div>
        <div v-if="canEdit" class="flex gap-1">
          <Button
            icon="pi pi-pencil"
            text
            rounded
            size="small"
            @click="emit('edit', ms)"
          />
          <Button
            icon="pi pi-trash"
            text
            rounded
            size="small"
            severity="danger"
            @click="emit('remove', ms)"
          />
        </div>
      </div>
      <div v-if="milestones.length === 0" class="py-4 text-center text-surface-400">
        マイルストーンがありません
      </div>
    </div>
  </div>
</template>
