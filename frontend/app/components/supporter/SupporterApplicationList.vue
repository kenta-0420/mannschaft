<script setup lang="ts">
import type { ApplicationItem } from '~/composables/useSupporterManagement'

defineProps<{
  applications: ApplicationItem[]
  selectedIds: number[]
  processingIds: number[]
  bulkApproving: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  approve: [id: number]
  reject: [id: number]
  bulkApprove: []
  toggleSelectAll: []
  toggleSelect: [id: number]
}>()

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('ja-JP')
}
</script>

<template>
  <div class="rounded-lg border p-4">
    <div class="mb-3 flex items-center justify-between">
      <h3 class="font-semibold">
        承認待ちの申請
        <Badge
          v-if="applications.length > 0"
          :value="applications.length"
          severity="warn"
          class="ml-2"
        />
      </h3>
      <Button
        v-if="applications.length > 0"
        label="一括承認"
        icon="pi pi-check-circle"
        size="small"
        :disabled="selectedIds.length === 0"
        :loading="bulkApproving"
        @click="emit('bulkApprove')"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>
    <div
      v-else-if="applications.length === 0"
      class="rounded-lg border border-dashed border-gray-300 py-8 text-center text-sm text-gray-500"
    >
      <i class="pi pi-inbox mb-2 text-2xl" />
      <p>承認待ちの申請はありません</p>
    </div>
    <div v-else class="space-y-2">
      <div class="flex items-center gap-2 border-b pb-2">
        <Checkbox
          :model-value="selectedIds.length === applications.length"
          binary
          @change="emit('toggleSelectAll')"
        />
        <span class="text-sm text-gray-500">すべて選択（{{ applications.length }}件）</span>
      </div>
      <div
        v-for="app in applications"
        :key="app.id"
        class="flex items-center gap-3 rounded-lg border p-3"
      >
        <Checkbox
          :model-value="selectedIds.includes(app.id)"
          binary
          @change="emit('toggleSelect', app.id)"
        />
        <Avatar
          :image="app.avatarUrl ?? undefined"
          :label="app.avatarUrl ? undefined : app.displayName.charAt(0)"
          shape="circle"
          size="normal"
        />
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ app.displayName }}</p>
          <p v-if="app.message" class="truncate text-sm text-gray-500">{{ app.message }}</p>
          <p class="text-xs text-gray-400">申請日: {{ formatDate(app.createdAt) }}</p>
        </div>
        <div class="flex shrink-0 gap-2">
          <Button
            label="承認"
            icon="pi pi-check"
            size="small"
            severity="success"
            :loading="processingIds.includes(app.id)"
            @click="emit('approve', app.id)"
          />
          <Button
            label="却下"
            icon="pi pi-times"
            size="small"
            severity="danger"
            outlined
            :loading="processingIds.includes(app.id)"
            @click="emit('reject', app.id)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
