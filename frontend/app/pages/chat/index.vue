<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const selectedChannel = ref<ChatChannelResponse | null>(null)
const showCreateDialog = ref(false)
const showContactSearch = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)
const leftTab = ref<'chat' | 'contacts' | 'requests'>('chat')

function onSelectChannel(ch: ChatChannelResponse) {
  selectedChannel.value = ch
  leftTab.value = 'chat'
}

function onCreated() {
  listRef.value?.refresh()
}

onMounted(() => {
  if (route.query.dm) {
    // DM パラメータがある場合は連絡先タブは不要、チャネルは親が開く想定
  }
})
</script>

<template>
  <div>
    <div class="mb-4">
      <h1 class="text-2xl font-bold">チャット</h1>
    </div>

    <div class="flex h-[calc(100vh-12rem)] overflow-hidden rounded-xl border border-surface-200">
      <div class="w-64 shrink-0 border-r border-surface-200 bg-surface-50 flex flex-col">
        <div class="flex border-b border-surface-200">
          <button
            class="flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'chat'
                ? 'border-b-2 border-primary text-primary'
                : 'text-gray-500 hover:text-gray-700'
            "
            @click="leftTab = 'chat'"
          >
            チャット
          </button>
          <button
            class="relative flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'contacts'
                ? 'border-b-2 border-primary text-primary'
                : 'text-gray-500 hover:text-gray-700'
            "
            @click="leftTab = 'contacts'"
          >
            連絡先
          </button>
          <button
            class="relative flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'requests'
                ? 'border-b-2 border-primary text-primary'
                : 'text-gray-500 hover:text-gray-700'
            "
            @click="leftTab = 'requests'"
          >
            申請
            <ContactRequestBadge class="absolute -right-1 -top-1" />
          </button>
        </div>
        <div class="flex-1 overflow-y-auto p-2">
          <template v-if="leftTab === 'chat'">
            <ChatChannelList
              ref="listRef"
              @select="onSelectChannel"
              @create="showCreateDialog = true"
            />
          </template>
          <template v-else-if="leftTab === 'contacts'">
            <div class="mb-2">
              <Button
                label="@ハンドルで追加"
                icon="pi pi-user-plus"
                size="small"
                class="w-full"
                outlined
                @click="showContactSearch = true"
              />
            </div>
            <ContactList />
          </template>
          <template v-else>
            <ContactRequestList />
          </template>
        </div>
      </div>

      <div class="flex-1 bg-surface-0">
        <ChatMessagePanel v-if="selectedChannel" :channel="selectedChannel" />
        <div v-else class="flex h-full flex-col items-center justify-center text-surface-400">
          <i class="pi pi-comments mb-3 text-4xl" />
          <p>チャンネルを選択してください</p>
        </div>
      </div>
    </div>

    <ChatCreateDialog v-model:visible="showCreateDialog" @created="onCreated" />

    <ContactSearchDialog v-model:visible="showContactSearch" @added="listRef?.refresh()" />
  </div>
</template>
