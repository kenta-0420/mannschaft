<script setup lang="ts">
import type { ContactRequestResponse } from '~/types/contact'

defineProps<{
  request: ContactRequestResponse
  type: 'received' | 'sent'
}>()

const emit = defineEmits<{
  accept: [id: number]
  reject: [id: number]
  cancel: [id: number]
}>()

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' })
}
</script>

<template>
  <div class="flex items-start gap-3 rounded-lg border border-surface-200 p-3">
    <Avatar
      :image="(type === 'received' ? request.requester : request.target).avatarUrl ?? undefined"
      :label="
        (type === 'received' ? request.requester : request.target).avatarUrl
          ? undefined
          : (type === 'received' ? request.requester : request.target).displayName.charAt(0)
      "
      shape="circle"
    />
    <div class="min-w-0 flex-1">
      <div class="font-medium">
        {{ (type === 'received' ? request.requester : request.target).displayName }}
      </div>
      <div
        v-if="(type === 'received' ? request.requester : request.target).contactHandle"
        class="text-xs text-gray-400"
      >
        @{{ (type === 'received' ? request.requester : request.target).contactHandle }}
      </div>
      <div v-if="request.message" class="mt-1 text-sm text-gray-600">{{ request.message }}</div>
      <div class="mt-1 text-xs text-gray-400">{{ formatDate(request.createdAt) }}</div>
      <div class="mt-2 flex gap-2">
        <template v-if="type === 'received'">
          <Button
            label="承認"
            icon="pi pi-check"
            size="small"
            @click="emit('accept', request.id)"
          />
          <Button
            label="拒否"
            icon="pi pi-times"
            size="small"
            severity="secondary"
            outlined
            @click="emit('reject', request.id)"
          />
        </template>
        <template v-else>
          <Button
            label="申請を取り消す"
            icon="pi pi-times"
            size="small"
            severity="secondary"
            outlined
            @click="emit('cancel', request.id)"
          />
        </template>
      </div>
    </div>
  </div>
</template>
