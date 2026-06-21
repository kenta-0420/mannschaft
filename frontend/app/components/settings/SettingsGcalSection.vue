<script setup lang="ts">
const gcalSyncSettings = defineModel<{
  personalSync: boolean
  teamSyncIds: string[]
  orgSyncIds: string[]
} | null>('gcalSyncSettings', { required: true })

defineProps<{
  gcalStatus: { isConnected: boolean; email: string | null; lastSyncedAt: string | null } | null
  gcalSyncing: boolean
  teams: { id: number; name: string; nickname1?: string | null }[]
  organizations: { id: number; name: string; nickname1?: string | null }[]
}>()

defineEmits<{
  connect: []
  disconnect: []
  saveSettings: []
  manualSync: []
  toggleTeamSync: [teamId: string]
  toggleOrgSync: [orgId: string]
}>()

const { formatDateTime } = useDatetime()

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
}
</script>

<template>
  <SectionCard :title="$t('settings.settings.gcal.section_title')">
    <div v-if="gcalStatus?.isConnected" class="space-y-4">
      <div class="flex items-center gap-3">
        <div
          class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
        >
          <i class="pi pi-check text-green-600" />
        </div>
        <div>
          <p class="font-medium text-green-700 dark:text-green-400">{{ $t('settings.settings.gcal.connected_label') }}</p>
          <p class="text-sm text-surface-500">{{ gcalStatus.email }}</p>
        </div>
      </div>
      <p class="text-xs text-surface-400">
        {{ $t('settings.settings.gcal.last_synced', { date: formatDate(gcalStatus.lastSyncedAt) }) }}
      </p>
      <div class="flex gap-2">
        <Button
          translate="no"
          :label="$t('settings.settings.gcal.manual_sync_button')"
          icon="pi pi-refresh"
          size="small"
          outlined
          :loading="gcalSyncing"
          @click="$emit('manualSync')"
        />
        <Button
          translate="no"
          :label="$t('settings.settings.gcal.disconnect_button')"
          icon="pi pi-times"
          size="small"
          severity="danger"
          outlined
          @click="$emit('disconnect')"
        />
      </div>
      <div v-if="gcalSyncSettings">
        <Divider />
        <h3 class="mb-3 text-sm font-semibold">{{ $t('settings.settings.gcal.sync_settings_title') }}</h3>
        <div class="mb-3 flex items-center justify-between">
          <div>
            <p class="text-sm font-medium">{{ $t('settings.settings.gcal.personal_calendar_label') }}</p>
            <p class="text-xs text-surface-500">{{ $t('settings.settings.gcal.personal_calendar_description') }}</p>
          </div>
          <ToggleSwitch v-model="gcalSyncSettings.personalSync" />
        </div>
        <div v-if="teams.length > 0" class="mb-3">
          <p class="mb-2 text-xs font-medium text-surface-500">{{ $t('settings.settings.gcal.team_calendars_label') }}</p>
          <div class="space-y-2">
            <div
              v-for="team in teams"
              :key="team.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ team.nickname1 || team.name }}</span>
              <ToggleSwitch
                :model-value="gcalSyncSettings.teamSyncIds.includes(String(team.id))"
                @update:model-value="$emit('toggleTeamSync', String(team.id))"
              />
            </div>
          </div>
        </div>
        <div v-if="organizations.length > 0" class="mb-3">
          <p class="mb-2 text-xs font-medium text-surface-500">{{ $t('settings.settings.gcal.org_calendars_label') }}</p>
          <div class="space-y-2">
            <div
              v-for="org in organizations"
              :key="org.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ org.nickname1 || org.name }}</span>
              <ToggleSwitch
                :model-value="gcalSyncSettings.orgSyncIds.includes(String(org.id))"
                @update:model-value="$emit('toggleOrgSync', String(org.id))"
              />
            </div>
          </div>
        </div>
        <Button translate="no" :label="$t('settings.settings.gcal.save_settings_button')" icon="pi pi-check" size="small" @click="$emit('saveSettings')" />
      </div>
    </div>
    <div v-else>
      <p class="mb-3 text-sm text-surface-500">
        {{ $t('settings.settings.gcal.not_connected_description') }}
      </p>
      <Button
        translate="no"
        :label="$t('settings.settings.gcal.connect_button')"
        icon="pi pi-external-link"
        @click="$emit('connect')"
      />
    </div>
  </SectionCard>
</template>
