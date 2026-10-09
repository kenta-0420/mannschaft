<script setup lang="ts">
import type { ActivitySyncField, ActivitySyncPreview } from '~/types/activityScheduleSync'
defineProps<{ preview: ActivitySyncPreview | null }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{
  apply: [selected: Record<number, ActivitySyncField[]>]
  scheduleOnly: []
  cancel: []
}>()
const selected = ref<Record<number, ActivitySyncField[]>>({})
const { t } = useI18n()
watch(visible, (open) => {
  if (open) selected.value = {}
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('activity.sync.title')"
    :style="{ width: '680px', maxWidth: '95vw' }"
    @hide="emit('cancel')"
  >
    <p class="mb-4">{{ t('activity.sync.description') }}</p>
    <div v-for="activity in preview?.activities" :key="activity.id" class="mb-4 space-y-3">
      <NuxtLink
        :to="`/activities/${activity.id}`"
        target="_blank"
        class="inline-flex min-h-11 items-center underline"
        >{{ t('activity.sync.record', { id: activity.id }) }} —
        {{ t(`activity.statusLabel.${activity.status}`) }}</NuxtLink
      >
      <div
        v-for="change in activity.changes"
        :key="change.field"
        class="space-y-1 border-b border-surface-200 pb-3 dark:border-surface-700"
      >
        <div class="flex items-center gap-2">
          <Checkbox
            v-if="!change.automatic"
            :input-id="`sync-${activity.id}-${change.field}`"
            :model-value="selected[activity.id] ?? []"
            :value="change.field"
            @update:model-value="selected[activity.id] = $event"
          />
          <label
            :for="`sync-${activity.id}-${change.field}`"
            class="inline-flex min-h-11 items-center"
            >{{ t(`activity.sync.fields.${change.field}`) }}</label
          >
          <Tag v-if="change.automatic" :value="t('activity.sync.automatic')" severity="info" />
        </div>
        <div class="grid min-w-0 grid-cols-1 gap-2 text-sm md:grid-cols-2">
          <p class="break-words">
            {{ t('activity.sync.current') }}: {{ change.currentValue ?? '—' }}
          </p>
          <p class="break-words">
            {{ t('activity.sync.updated') }}: {{ change.scheduleValue ?? '—' }}
          </p>
        </div>
      </div>
    </div>
    <template #footer>
      <div class="flex flex-wrap gap-2">
        <Button
          class="min-h-11 min-w-11"
          :label="t('button.cancel')"
          severity="secondary"
          data-testid="activity-sync-cancel"
          @click="emit('cancel')"
        />
        <Button
          class="min-h-11 min-w-11"
          :label="t('activity.sync.scheduleOnly')"
          severity="secondary"
          outlined
          data-testid="activity-sync-schedule-only"
          @click="emit('scheduleOnly')"
        />
        <Button
          class="min-h-11 min-w-11"
          :label="t('activity.sync.apply')"
          data-testid="activity-sync-apply"
          @click="emit('apply', selected)"
        />
      </div>
    </template>
  </Dialog>
</template>
