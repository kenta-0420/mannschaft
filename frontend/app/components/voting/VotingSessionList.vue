<script setup lang="ts">
const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; slug: string }>()
const { t } = useI18n()
const { relativeTime } = useRelativeTime()
const { sessions, loading, error, canCreate, showCreateDialog, saving, form, errors, load, openCreateDialog, handleCreate } = useVotingSessions(props.scopeType, toRef(props, 'slug'))
useHead({ title: () => t('voting.title') })
const modes = computed(() => [
  { label: t('voting.modes.WRITTEN'), value: 'WRITTEN' },
  { label: t('voting.modes.MEETING'), value: 'MEETING' },
])
</script>

<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <PageHeader :title="t('voting.title')" />
      <Button v-if="canCreate" :label="t('voting.create')" icon="pi pi-plus" size="small" @click="openCreateDialog" />
    </div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" :show-retry="true" @retry="load" />
    <DashboardEmptyState v-else-if="sessions.length === 0" icon="pi pi-check-square" :message="t('voting.empty')" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard v-for="session in sessions" :key="session.id">
        <div class="mb-2 flex flex-wrap items-center gap-2 text-xs">
          <span v-if="session.status" class="rounded bg-surface-100 px-2 py-1 text-surface-600 dark:bg-surface-800 dark:text-surface-200">{{ t(`voting.status.${session.status}`) }}</span>
          <span v-if="session.resolutionMode">{{ t(`voting.modes.${session.resolutionMode}`) }}</span>
          <span v-if="session.isAnonymous"><i class="pi pi-eye-slash" /> {{ t('voting.anonymous') }}</span>
        </div>
        <h3 class="text-sm font-semibold">{{ session.title }}</h3>
        <div class="mt-1 flex flex-wrap items-center gap-3 text-xs text-surface-500">
          <span v-if="session.motionCount !== undefined">{{ t('voting.motions', { count: session.motionCount }) }}</span>
          <span v-if="session.eligibleCount !== undefined">{{ t('voting.eligible', { count: session.eligibleCount }) }}</span>
          <span v-if="session.createdAt">{{ relativeTime(session.createdAt) }}</span>
        </div>
      </SectionCard>
    </div>
    <Dialog v-model:visible="showCreateDialog" modal :header="t('voting.create')" :style="{ width: '28rem', maxWidth: '95vw' }" :closable="!saving" :close-on-escape="!saving">
      <form id="voting-create" class="flex flex-col gap-4 py-2" @submit.prevent="handleCreate">
        <div class="flex flex-col gap-1">
          <label for="voting-title">{{ t('voting.sessionTitle') }} *</label>
          <InputText id="voting-title" v-model="form.title" :placeholder="t('voting.titlePlaceholder')" :maxlength="200" required :invalid="!!errors.title" :disabled="saving" />
        </div>
        <div class="flex flex-col gap-1">
          <label for="voting-description">{{ t('voting.description') }}</label>
          <Textarea id="voting-description" v-model="form.description" rows="3" :disabled="saving" />
        </div>
        <div class="flex flex-col gap-1">
          <label for="voting-mode">{{ t('voting.mode') }}</label>
          <Select v-model="form.resolutionMode" input-id="voting-mode" :options="modes" option-label="label" option-value="value" :disabled="saving" />
        </div>
        <div v-if="form.resolutionMode === 'MEETING'" class="flex flex-col gap-1">
          <label for="voting-date">{{ t('voting.meetingDate') }} *</label>
          <DatePicker v-model="form.meetingDate" input-id="voting-date" date-format="yy/mm/dd" show-icon :invalid="!!errors.meetingDate" :disabled="saving" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isAnonymous" :binary="true" input-id="voting-anon" :disabled="saving" />
          <label for="voting-anon">{{ t('voting.anonymous') }}</label>
        </div>
        <p class="text-xs" :class="errors.title || errors.meetingDate ? 'text-red-600' : 'text-surface-500'" :role="errors.title || errors.meetingDate ? 'alert' : undefined">{{ t('voting.requirements') }}</p>
      </form>
      <template #footer>
        <Button :label="t('voting.cancel')" text :disabled="saving" @click="showCreateDialog = false" />
        <Button type="submit" form="voting-create" :label="t('voting.submit')" icon="pi pi-check" :loading="saving" :disabled="saving || !canCreate" />
      </template>
    </Dialog>
  </div>
</template>
