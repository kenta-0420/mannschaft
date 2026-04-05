<script setup lang="ts">
defineProps<{
  gcalStatus: { isConnected: boolean; email: string | null; lastSyncedAt: string | null } | null
  gcalSyncSettings: {
    personalSync: boolean
    teamSyncIds: number[]
    orgSyncIds: number[]
  } | null
  gcalSyncing: boolean
  teams: { id: number; name: string; nickname1?: string | null }[]
  organizations: { id: number; name: string; nickname1?: string | null }[]
}>()

defineEmits<{
  connect: []
  disconnect: []
  saveSettings: []
  manualSync: []
  toggleTeamSync: [teamId: number]
  toggleOrgSync: [orgId: number]
}>()

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
</script>

<template>
  <SectionCard title="Google Calendar 連携">
    <div v-if="gcalStatus?.isConnected" class="space-y-4">
      <div class="flex items-center gap-3">
        <div
          class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
        >
          <i class="pi pi-check text-green-600" />
        </div>
        <div>
          <p class="font-medium text-green-700 dark:text-green-400">接続中</p>
          <p class="text-sm text-surface-500">{{ gcalStatus.email }}</p>
        </div>
      </div>
      <p class="text-xs text-surface-400">
        最終同期: {{ formatDate(gcalStatus.lastSyncedAt) }}
      </p>
      <div class="flex gap-2">
        <Button
          label="手動同期"
          icon="pi pi-refresh"
          size="small"
          outlined
          :loading="gcalSyncing"
          @click="$emit('manualSync')"
        />
        <Button
          label="連携解除"
          icon="pi pi-times"
          size="small"
          severity="danger"
          outlined
          @click="$emit('disconnect')"
        />
      </div>
      <div v-if="gcalSyncSettings">
        <Divider />
        <h3 class="mb-3 text-sm font-semibold">同期設定</h3>
        <div class="mb-3 flex items-center justify-between">
          <div>
            <p class="text-sm font-medium">個人カレンダー</p>
            <p class="text-xs text-surface-500">個人の予定をGoogleカレンダーに同期</p>
          </div>
          <ToggleSwitch v-model="gcalSyncSettings.personalSync" />
        </div>
        <div v-if="teams.length > 0" class="mb-3">
          <p class="mb-2 text-xs font-medium text-surface-500">チームカレンダー</p>
          <div class="space-y-2">
            <div
              v-for="team in teams"
              :key="team.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ team.nickname1 || team.name }}</span>
              <ToggleSwitch
                :model-value="gcalSyncSettings.teamSyncIds.includes(team.id)"
                @update:model-value="$emit('toggleTeamSync', team.id)"
              />
            </div>
          </div>
        </div>
        <div v-if="organizations.length > 0" class="mb-3">
          <p class="mb-2 text-xs font-medium text-surface-500">組織カレンダー</p>
          <div class="space-y-2">
            <div
              v-for="org in organizations"
              :key="org.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ org.nickname1 || org.name }}</span>
              <ToggleSwitch
                :model-value="gcalSyncSettings.orgSyncIds.includes(org.id)"
                @update:model-value="$emit('toggleOrgSync', org.id)"
              />
            </div>
          </div>
        </div>
        <Button label="設定を保存" icon="pi pi-check" size="small" @click="$emit('saveSettings')" />
      </div>
    </div>
    <div v-else>
      <p class="mb-3 text-sm text-surface-500">
        Googleアカウントと連携して、カレンダーを同期できます
      </p>
      <Button
        label="Googleアカウントに接続"
        icon="pi pi-external-link"
        @click="$emit('connect')"
      />
    </div>
  </SectionCard>
</template>
