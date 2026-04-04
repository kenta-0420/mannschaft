<script setup lang="ts">
import type { ContactResponse } from '~/types/contact'

defineProps<{
  contact: ContactResponse
}>()

const emit = defineEmits<{
  remove: [userId: number]
  dm: [userId: number]
  block: [userId: number]
}>()
</script>

<template>
  <div class="flex items-center gap-3 rounded-lg p-3 transition-colors hover:bg-surface-50">
    <Avatar
      :image="contact.user.avatarUrl ?? undefined"
      :label="
        contact.user.avatarUrl
          ? undefined
          : (contact.customName || contact.user.displayName).charAt(0)
      "
      shape="circle"
      size="normal"
    />
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-1">
        <span class="truncate font-medium">
          {{ contact.customName || contact.user.displayName }}
        </span>
        <i v-if="contact.isPinned" class="pi pi-star-fill shrink-0 text-xs text-yellow-400" />
      </div>
      <div v-if="contact.user.contactHandle" class="truncate text-xs text-gray-400">
        @{{ contact.user.contactHandle }}
      </div>
    </div>
    <div class="flex shrink-0 items-center gap-1">
      <Button
        v-tooltip.top="'DMを開く'"
        icon="pi pi-comments"
        size="small"
        text
        rounded
        severity="secondary"
        @click="emit('dm', contact.user.id)"
      />
      <Button icon="pi pi-ellipsis-v" size="small" text rounded severity="secondary" @click.stop />
    </div>
  </div>
</template>
