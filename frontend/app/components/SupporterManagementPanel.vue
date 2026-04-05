<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const {
  autoApprove,
  settingsLoading,
  settingsSaving,
  supporters,
  supportersLoading,
  applicationsLoading,
  selectedApplicationIds,
  bulkApproving,
  processingIds,
  pendingApplications,
  saveAutoApprove,
  approve,
  reject,
  bulkApprove,
  toggleSelectAll,
  toggleSelect,
  init,
} = useSupporterManagement(toRef(props, 'scopeType'), toRef(props, 'scopeId'))

onMounted(init)
</script>

<template>
  <div class="space-y-6">
    <div class="rounded-lg border p-4">
      <h3 class="mb-3 font-semibold">承認設定</h3>
      <div v-if="settingsLoading" class="flex items-center gap-2 text-gray-500">
        <i class="pi pi-spin pi-spinner" />
        <span class="text-sm">読み込み中...</span>
      </div>
      <div v-else class="flex items-center justify-between">
        <div>
          <p class="font-medium">自動承認</p>
          <p class="text-sm text-gray-500">ONにするとサポーター申請を自動で承認します</p>
        </div>
        <ToggleSwitch
          :model-value="autoApprove"
          :disabled="settingsSaving"
          @update:model-value="saveAutoApprove"
        />
      </div>
    </div>

    <SupporterApplicationList
      v-if="!autoApprove"
      :applications="pendingApplications"
      :selected-ids="selectedApplicationIds"
      :processing-ids="processingIds"
      :bulk-approving="bulkApproving"
      :loading="applicationsLoading"
      @approve="approve"
      @reject="reject"
      @bulk-approve="bulkApprove"
      @toggle-select-all="toggleSelectAll"
      @toggle-select="toggleSelect"
    />

    <SupporterListGrid :supporters="supporters" :loading="supportersLoading" />
  </div>
</template>
