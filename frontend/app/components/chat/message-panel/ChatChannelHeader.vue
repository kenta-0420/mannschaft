<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

defineProps<{
  channel: ChatChannelResponse
}>()

const emit = defineEmits<{
  invite: []
}>()
</script>

<template>
  <div class="flex items-center gap-3 border-b border-surface-200 dark:border-surface-700 px-4 py-3">
    <i
      :class="
        channel.channelType === 'DIRECT'
          ? 'pi pi-user'
          : channel.isPrivate
            ? 'pi pi-lock'
            : 'pi pi-hashtag'
      "
      class="text-surface-400"
    />
    <div>
      <h3 class="text-sm font-semibold">
        {{
          channel.channelType === 'DIRECT' && channel.dmPartner
            ? channel.dmPartner.displayName
            : channel.name
        }}
      </h3>
      <p v-if="channel.description" class="text-xs text-surface-400">{{ channel.description }}</p>
    </div>
    <div class="ml-auto flex items-center gap-2">
      <Button
        v-if="channel.channelType === 'DIRECT'"
        v-tooltip.bottom="'Zimmerに招待'"
        icon="pi pi-user-plus"
        text
        rounded
        size="small"
        severity="secondary"
        @click="emit('invite')"
      />
      <span class="flex items-center gap-1 text-xs text-surface-400">
        <i class="pi pi-users" />
        {{ channel.memberCount }}
      </span>
    </div>
  </div>
</template>
