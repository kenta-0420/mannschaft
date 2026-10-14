<script setup lang="ts">
import type { ArchiveTeamSummary } from '~/composables/useTeamArchiveAction'
const props = defineProps<{ team: ArchiveTeamSummary, refresh: () => Promise<void> }>()
const slug = computed(() => props.team.slug ?? '')
const archived = computed(() => !!props.team.isArchived)
const target = computed(() => props.team)
const { available, busy, syncError, request, retrySync } = useTeamArchiveAction(slug, archived, props.refresh, target)
</script>

<template>
  <Button
    v-if="available"
    :label="$t('teamShell.archive.unarchive')"
    icon="pi pi-refresh"
    severity="secondary"
    outlined
    class="min-h-11"
    :loading="busy"
    :disabled="busy || syncError"
    data-testid="team-unarchive-button"
    @click="request"
  />
  <span v-if="syncError" role="alert" class="text-sm text-red-500">
    {{ $t('teamShell.archive.syncError') }}
    <Button :label="$t('common.scopeShell.retry')" text :disabled="busy" @click="retrySync" />
  </span>
</template>
