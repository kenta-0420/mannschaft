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
  getThread,
  sendMessage,
} = useChatApi()
const { showSuccess, showError } = useNotification()

useChatChannelEventListener(props.channel.id)

const messages = ref<ChatMessageResponse[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const scrollContainer = ref<HTMLElement | null>(null)

// --- スレッド展開パネル ---
const threadParent = ref<ChatMessageResponse | null>(null)
const threadMessages = ref<ChatMessageResponse[]>([])
const threadLoading = ref(false)
const threadNextCursor = ref<string | null>(null)
const threadHasMore = ref(false)
const threadReplyBody = ref('')
const threadReplySending = ref(false)

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

// --- スレッド展開 ---
async function openThread(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  threadParent.value = msg
  threadMessages.value = []
  threadReplyBody.value = ''
  await loadThread(messageId)
}

function closeThread() {
  threadParent.value = null
  threadMessages.value = []
  threadNextCursor.value = null
  threadHasMore.value = false
}

async function loadThread(messageId: number, cursor?: string) {
  threadLoading.value = true
  try {
    const res = await getThread(messageId, cursor)
    if (!cursor) {
      threadMessages.value = res.data
    } else {
      threadMessages.value.push(...res.data)
    }
    threadNextCursor.value = res.meta.nextCursor
    threadHasMore.value = res.meta.hasMore
  } catch {
    showError('スレッドの取得に失敗しました')
  } finally {
    threadLoading.value = false
  }
}

async function sendThreadReply() {
  if (!threadParent.value || !threadReplyBody.value.trim()) return
  threadReplySending.value = true
  try {
    await sendMessage(props.channel.id, threadReplyBody.value.trim(), threadParent.value.id)
    threadReplyBody.value = ''
    await loadThread(threadParent.value.id)
    const msg = messages.value.find((m) => m.id === threadParent.value!.id)
    if (msg) msg.replyCount++
  } catch {
    showError('返信に失敗しました')
  } finally {
    threadReplySending.value = false
  }
}

function onSent() {
  loadMessages()
}

watch(
  () => props.channel.id,
  () => {
    closeThread()
    loadMessages()
  },
)
onMounted(() => loadMessages())
</script>

<template>
  <div class="flex h-full">
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

    <!-- メインメッセージエリア -->
    <div class="flex flex-1 flex-col" :class="threadParent ? 'border-r border-surface-200 dark:border-surface-700' : ''">

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
      @reply="openThread"
      />
      <DashboardEmptyState v-if="messages.length === 0 && !loading" icon="pi pi-comments" message="まだメッセージがありません" />
    </div>

    <!-- 入力 -->
    <ChatMessageInput :channel-id="channel.id" :disabled="channel.isArchived" @sent="onSent" />
    </div>

    <!-- スレッド展開パネル -->
    <div v-if="threadParent" class="flex w-80 flex-shrink-0 flex-col bg-surface-50 dark:bg-surface-900">
      <div class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-700">
        <span class="text-sm font-semibold">スレッド</span>
        <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="closeThread" />
      </div>
      <div class="border-b border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-800">
        <span class="text-xs font-semibold">{{ threadParent.sender?.displayName || '不明' }}</span>
        <p class="mt-0.5 text-sm text-surface-600 dark:text-surface-400">{{ threadParent.body }}</p>
      </div>
      <div class="flex-1 overflow-y-auto">
        <div v-if="threadLoading && threadMessages.length === 0" class="flex justify-center py-6">
          <ProgressSpinner style="width: 32px; height: 32px" />
        </div>
        <ChatMessageBubble
          v-for="msg in threadMessages"
          :key="msg.id"
          :message="msg"
          :can-pin="canPin"
          :can-delete="canDelete"
          @reaction="(id, emoji) => onReaction(id, emoji)"
          @bookmark="onBookmark"
          @delete="onDelete"
          @pin="onPin"
        />
        <div v-if="threadHasMore" class="flex justify-center py-2">
          <Button label="さらに読む" text size="small" :loading="threadLoading" @click="loadThread(threadParent!.id, threadNextCursor!)" />
        </div>
      </div>
      <div class="border-t border-surface-200 p-3 dark:border-surface-700">
        <div class="flex gap-2">
          <InputText
            v-model="threadReplyBody"
            placeholder="返信を入力..."
            class="flex-1 text-sm"
            @keydown.enter.prevent="sendThreadReply"
          />
          <Button
            icon="pi pi-send"
            size="small"
            :loading="threadReplySending"
            :disabled="!threadReplyBody.trim()"
            @click="sendThreadReply"
          />
        </div>
      </div>
    </div>
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
