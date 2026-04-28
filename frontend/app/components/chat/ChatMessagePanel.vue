<script setup lang="ts">
import type { ChatChannelResponse, ChatMessageResponse } from '~/types/chat'

const props = defineProps<{
  channel: ChatChannelResponse
  canPin?: boolean
  canDelete?: boolean
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  channelCreated: [channel: ChatChannelResponse]
}>()

const showInviteDialog = ref(false)

const {
  getMessages,
  markAsRead,
  addReaction,
  removeReaction,
  togglePin,
  deleteMessage,
  bookmarkMessage,
  removeBookmark,
} = useChatApi()
const { showSuccess, showError } = useNotification()

useChatChannelEventListener(props.channel.id)

const messages = ref<ChatMessageResponse[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const scrollContainer = ref<HTMLElement | null>(null)

async function loadMessages(cursor?: string) {
  loading.value = true
  try {
    const res = await getMessages(props.channel.id, cursor)
    if (!cursor) {
      messages.value = res.data.reverse()
      await nextTick()
      scrollToBottom()
    } else {
      const prevHeight = scrollContainer.value?.scrollHeight || 0
      messages.value.unshift(...res.data.reverse())
      await nextTick()
      if (scrollContainer.value) {
        scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight - prevHeight
      }
    }
    nextCursor.value = res.meta.nextCursor
    hasMore.value = res.meta.hasMore
    markAsRead(props.channel.id)
  } catch {
    showError('メッセージの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function scrollToBottom() {
  if (scrollContainer.value) {
    scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
  }
}

async function onReaction(messageId: number, emoji: string) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    if (msg.myReactions.includes(emoji)) {
      await removeReaction(messageId, emoji)
      msg.myReactions = msg.myReactions.filter((e) => e !== emoji)
      msg.reactionSummary[emoji] = (msg.reactionSummary[emoji] || 1) - 1
      if (msg.reactionSummary[emoji] <= 0) {
        msg.reactionSummary = Object.fromEntries(
          Object.entries(msg.reactionSummary).filter(([k]) => k !== emoji),
        )
      }
    } else {
      await addReaction(messageId, emoji)
      msg.myReactions.push(emoji)
      msg.reactionSummary[emoji] = (msg.reactionSummary[emoji] || 0) + 1
    }
  } catch {
    showError('リアクションに失敗しました')
  }
}

async function onPin(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    await togglePin(messageId, !msg.isPinned)
    msg.isPinned = !msg.isPinned
    showSuccess(msg.isPinned ? 'ピン留めしました' : 'ピン解除しました')
  } catch {
    showError('ピン操作に失敗しました')
  }
}

async function onDelete(messageId: number) {
  try {
    await deleteMessage(messageId)
    const msg = messages.value.find((m) => m.id === messageId)
    if (msg) {
      msg.isDeleted = true
      msg.body = null
      msg.sender = null
    }
    showSuccess('メッセージを削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

async function onBookmark(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    if (msg.isBookmarked) {
      await removeBookmark(messageId)
      msg.isBookmarked = false
    } else {
      await bookmarkMessage(messageId)
      msg.isBookmarked = true
    }
  } catch {
    showError('ブックマークに失敗しました')
  }
}

function onSent() {
  loadMessages()
}

watch(
  () => props.channel.id,
  () => loadMessages(),
)
onMounted(() => loadMessages())
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- Zimmerヘッダー -->
    <div class="flex items-center gap-3 border-b border-surface-200 px-4 py-3">
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
          @click="showInviteDialog = true"
        />
        <span class="flex items-center gap-1 text-xs text-surface-400">
          <i class="pi pi-users" />
          {{ channel.memberCount }}
        </span>
      </div>
    </div>

    <!-- メッセージ一覧 -->
    <div ref="scrollContainer" class="flex-1 overflow-y-auto">
      <div v-if="hasMore" class="flex justify-center py-2">
        <Button
          label="過去のメッセージを読み込む"
          text
          size="small"
          :loading="loading"
          @click="loadMessages(nextCursor!)"
        />
      </div>
      <ChatMessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :can-pin="canPin"
        :can-delete="canDelete"
        @reaction="onReaction"
        @pin="onPin"
        @delete="onDelete"
        @bookmark="onBookmark"
      />
      <DashboardEmptyState v-if="messages.length === 0 && !loading" icon="pi pi-comments" message="まだメッセージがありません" />
    </div>

    <!-- 入力 -->
    <ChatMessageInput :channel-id="channel.id" :disabled="channel.isArchived" @sent="onSent" />
  </div>

  <ChatInviteToZimmerDialog
    v-model:visible="showInviteDialog"
    :channel-id="channel.id"
    :dm-partner-user-id="channel.dmPartner?.id"
    :team-id="teamId"
    :organization-id="organizationId"
    @created="(ch) => emit('channelCreated', ch)"
  />
</template>
