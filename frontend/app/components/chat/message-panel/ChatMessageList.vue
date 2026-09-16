<script setup lang="ts">
import type { ChatMessageResponse } from '~/types/chat'

defineProps<{
  messages: ChatMessageResponse[]
  loading: boolean
  hasMore: boolean
  nextCursor: string | null
  canPin?: boolean
  canDelete?: boolean
  /** 承諾/辞退 API 実行中の招待トークン（招待カードのローディング表示用・F04.12） */
  pendingInviteToken?: string | null
}>()

const emit = defineEmits<{
  loadMore: [cursor: string]
  reaction: [messageId: number, emoji: string]
  pin: [messageId: number]
  delete: [messageId: number]
  bookmark: [messageId: number]
  reply: [messageId: number]
  inviteJoin: [messageId: number, token: string]
  inviteDecline: [messageId: number, token: string]
}>()

const scrollContainer = ref<HTMLElement | null>(null)

defineExpose({ scrollContainer })
</script>

<template>
  <div ref="scrollContainer" class="flex-1 overflow-y-auto">
    <div v-if="hasMore" class="flex justify-center py-2">
      <Button
        label="過去のメッセージを読み込む"
        text
        size="small"
        :loading="loading"
        @click="emit('loadMore', nextCursor!)"
      />
    </div>
    <ChatMessageBubble
      v-for="msg in messages"
      :key="msg.id"
      :message="msg"
      :can-pin="canPin"
      :can-delete="canDelete"
      :pending-invite-token="pendingInviteToken"
      @reaction="(messageId: number, emoji: string) => emit('reaction', messageId, emoji)"
      @pin="(messageId: number) => emit('pin', messageId)"
      @delete="(messageId: number) => emit('delete', messageId)"
      @bookmark="(messageId: number) => emit('bookmark', messageId)"
      @reply="(messageId: number) => emit('reply', messageId)"
      @invite-join="(messageId: number, token: string) => emit('inviteJoin', messageId, token)"
      @invite-decline="(messageId: number, token: string) => emit('inviteDecline', messageId, token)"
    />
    <DashboardEmptyState
      v-if="messages.length === 0 && !loading"
      icon="pi pi-comments"
      message="まだメッセージがありません"
    />
  </div>
</template>
