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
        channel.dmPartner
          ? 'pi pi-user'
          : channel.settings.isPrivate
            ? 'pi pi-lock'
            : 'pi pi-hashtag'
      "
      class="text-surface-400"
    />
    <div>
      <h3 class="text-sm font-semibold">
        {{ channel.dmPartner ? channel.dmPartner.displayName : channel.meta.name }}
      </h3>
      <p v-if="channel.meta.description" class="text-xs text-surface-400">
        {{ channel.meta.description }}
      </p>
    </div>
    <div class="ml-auto flex items-center gap-2">
      <Button
        v-if="channel.identity.channelType === 'DM'"
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
