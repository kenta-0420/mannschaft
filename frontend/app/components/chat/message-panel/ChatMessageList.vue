<script setup lang="ts">
import type { ChatMessageResponse } from '~/types/chat'

const props = defineProps<{
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
  edit: [messageId: number]
  bookmark: [messageId: number]
  reply: [messageId: number]
  inviteJoin: [messageId: number, token: string]
  inviteDecline: [messageId: number, token: string]
}>()

const authStore = useAuthStore()

/** ログイン中ユーザー本人の投稿か（編集・削除の出し分け用・CMP-261105-1507） */
function isMine(message: ChatMessageResponse): boolean {
  const me = authStore.user?.id
  return me != null && message.sender?.id === me
}

const scrollContainer = ref<HTMLElement | null>(null)

defineExpose({ scrollContainer })
</script>

<template>
  <div ref="scrollContainer" class="flex-1 overflow-y-auto">
    <div v-if="hasMore" class="flex justify-center py-2">
      <Button
        :label="$t('chat.message.loadOlder')"
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
      :can-delete="props.canDelete"
      :is-mine="isMine(msg)"
      :pending-invite-token="pendingInviteToken"
      @reaction="(messageId: number, emoji: string) => emit('reaction', messageId, emoji)"
      @pin="(messageId: number) => emit('pin', messageId)"
      @delete="(messageId: number) => emit('delete', messageId)"
      @edit="(messageId: number) => emit('edit', messageId)"
      @bookmark="(messageId: number) => emit('bookmark', messageId)"
      @reply="(messageId: number) => emit('reply', messageId)"
      @invite-join="(messageId: number, token: string) => emit('inviteJoin', messageId, token)"
      @invite-decline="(messageId: number, token: string) => emit('inviteDecline', messageId, token)"
    />
    <DashboardEmptyState
      v-if="messages.length === 0 && !loading"
      icon="pi pi-comments"
      :message="$t('chat.message.empty')"
    />
  </div>
</template>
