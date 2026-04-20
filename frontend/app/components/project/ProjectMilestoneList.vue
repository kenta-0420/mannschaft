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
      <h2 class="text-lg font-semibold">{{ $t('project.milestones') }}</h2>
      <Button
        v-if="canEdit"
        icon="pi pi-plus"
        :label="$t('button.create')"
        text
        size="small"
        @click="emit('create')"
      />
    </div>
    <div class="flex flex-col gap-2">
      <div
        v-for="ms in milestones"
        :key="ms.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
        :class="ms.isLocked ? 'opacity-60' : ''"
      >
        <div class="flex items-center justify-between">
          <div class="flex flex-1 items-center gap-3">
            <Checkbox
              :model-value="ms.completed"
              :binary="true"
              :disabled="ms.isLocked"
              @update:model-value="emit('toggleComplete', ms)"
            />
            <div class="flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <p :class="ms.completed ? 'text-surface-400 line-through' : 'font-medium'">
                  {{ ms.title }}
                </p>
                <LockedTodoBadge
                  :locked="ms.isLocked"
                  :locked-by-title="ms.lockedByMilestoneTitle"
                />
                <Tag
                  v-if="ms.completionMode === 'MANUAL'"
                  severity="info"
                  icon="pi pi-user-edit"
                  :value="$t('project.completion_mode_manual')"
                />
                <Tag
                  v-else
                  severity="secondary"
                  icon="pi pi-cog"
                  :value="$t('project.completion_mode_auto')"
                />
                <Tag
                  v-if="ms.forceUnlocked"
                  severity="danger"
                  icon="pi pi-unlock"
                  :value="$t('project.force_unlocked')"
                />
              </div>
              <p v-if="ms.dueDate" class="mt-1 text-xs text-surface-500">
                {{ $t('project.due_date') }}: {{ ms.dueDate }}
              </p>
              <p
                v-if="ms.lockedTodoCount > 0"
                class="mt-1 text-xs text-amber-600 dark:text-amber-400"
              >
                <i class="pi pi-lock mr-1" />
                {{ $t('project.locked_todo_count', { count: ms.lockedTodoCount }) }}
              </p>
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
        <div class="mt-2 flex items-center gap-2">
          <ProgressBar
            :value="Math.round(ms.progressRate)"
            :show-value="false"
            class="flex-1"
            style="height: 6px"
          />
          <span class="w-12 text-right text-xs text-surface-500">
            {{ ms.progressRate.toFixed(1) }}%
          </span>
        </div>
      </div>
      <div v-if="milestones.length === 0" class="py-4 text-center text-surface-400">
        {{ $t('project.no_milestones') }}
      </div>
    </div>
  </div>
</template>
