<script setup lang="ts">
import type { JoinRequestResponse } from '~/composables/useJoinRequestApi'

defineProps<{
  requests: JoinRequestResponse[]
  processingIds: string[]
  loading: boolean
}>()

const emit = defineEmits<{
  approve: [id: string]
  reject: [id: string]
}>()

const { formatDate } = useDatetime()
</script>

<template>
  <div class="rounded-lg border p-4">
    <div class="mb-3 flex items-center justify-between">
      <h3 class="font-semibold">
        {{ $t('joinRequest.admin.pendingTitle') }}
        <Badge
          v-if="requests.length > 0"
          :value="requests.length"
          severity="warn"
          class="ml-2"
        />
      </h3>
    </div>

    <div v-if="loading" class="flex justify-center py-6">
      <LoadingBounce />
    </div>
    <div
      v-else-if="requests.length === 0"
      class="rounded-lg border border-dashed border-gray-300 py-8 text-center text-sm text-gray-500"
    >
      <i class="pi pi-inbox mb-2 text-2xl" />
      <p>{{ $t('joinRequest.admin.empty') }}</p>
    </div>
    <div v-else class="space-y-2">
      <div
        v-for="req in requests"
        :key="req.id"
        class="flex items-center gap-3 rounded-lg border p-3"
        data-testid="join-request-row"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ $t('joinRequest.admin.requesterLabel', { userId: req.requesterUserId }) }}
          </p>
          <p v-if="req.message" class="truncate text-sm text-gray-500">{{ req.message }}</p>
          <p class="text-xs text-gray-400">
            {{ $t('joinRequest.admin.requestedAt') }}: {{ formatDate(req.createdAt) }}
          </p>
        </div>
        <div class="flex shrink-0 gap-2">
          <Button
            :label="$t('joinRequest.admin.approve')"
            icon="pi pi-check"
            size="small"
            severity="success"
            :loading="processingIds.includes(req.id)"
            @click="emit('approve', req.id)"
          />
          <Button
            :label="$t('joinRequest.admin.reject')"
            icon="pi pi-times"
            size="small"
            severity="danger"
            outlined
            :loading="processingIds.includes(req.id)"
            @click="emit('reject', req.id)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
