<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const selectedChannel = ref<ChatChannelResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void; refreshAndSelect: (id: number) => void } | null>(null)

function onSelectChannel(ch: ChatChannelResponse) {
  selectedChannel.value = ch
}

function onCreated() {
  listRef.value?.refresh()
}

async function onChannelCreated(ch: ChatChannelResponse) {
  await listRef.value?.refreshAndSelect(ch.id)
  selectedChannel.value = ch
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="チャット" />
    </div>

    <div class="flex h-[calc(100vh-12rem)] overflow-hidden rounded-xl border border-surface-300">
      <div class="w-64 shrink-0 border-r border-surface-200 bg-surface-50">
        <ChatChannelList
          ref="listRef"
          :organization-id="orgId"
          @select="onSelectChannel"
          @create="showCreateDialog = true"
        />
      </div>

      <div class="flex-1 bg-surface-0">
        <ChatMessagePanel
          v-if="selectedChannel"
          :channel="selectedChannel"
          :can-pin="isAdminOrDeputy"
          :can-delete="isAdmin"
          :organization-id="orgId"
          @channel-created="onChannelCreated"
        />
        <DashboardEmptyState v-else icon="pi pi-comments" message="チャンネルを選択してください" />
      </div>
    </div>

    <ChatCreateDialog
      v-model:visible="showCreateDialog"
      :organization-id="orgId"
      @created="onCreated"
    />
  </div>
</template>
