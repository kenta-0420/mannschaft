<script setup lang="ts">
import type { ChatMessageResponse } from '~/types/chat'

const props = defineProps<{
  rootMessageId: number
  channelId: number
  /** 他人の返信も削除できるか（チャンネル OWNER/ADMIN・スコープ管理者。F04.2 §5） */
  canDelete?: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const { t } = useI18n()
const {
  getThread,
  sendMessage,
  addReaction,
  removeReaction,
  togglePin,
  editMessage,
  deleteMessage,
  bookmarkMessage,
  removeBookmark,
} = useChatApi()
const { showError, showSuccess } = useNotification()
const authStore = useAuthStore()

/** ログイン中ユーザー本人の投稿か（編集・削除の出し分け用・CMP-261105-1507） */
function isMine(message: ChatMessageResponse): boolean {
  const me = authStore.user?.id
  return me != null && message.sender?.id === me
}

const rootMessage = ref<ChatMessageResponse | null>(null)
const messages = ref<ChatMessageResponse[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)

const replyBody = ref('')
const replySending = ref(false)

async function loadThread(cursor?: string) {
  loading.value = true
  try {
    const res = await getThread(props.rootMessageId, cursor)
    rootMessage.value = res.data.root
    if (!cursor) {
      messages.value = res.data.messages
    } else {
      messages.value.push(...res.data.messages)
    }
    nextCursor.value = res.data.nextCursor
    hasMore.value = res.data.hasMore
  } catch {
    showError(t('chat.thread.reply'))
  } finally {
    loading.value = false
  }
}

async function sendReply() {
  if (!replyBody.value.trim()) return
  replySending.value = true
  try {
    await sendMessage(props.channelId, replyBody.value.trim(), props.rootMessageId)
    replyBody.value = ''
    await loadThread()
  } catch {
    showError(t('chat.thread.reply'))
  } finally {
    replySending.value = false
  }
}

async function onReaction(messageId: number, emoji: string) {
  const msg = messages.value.find((m) => m.id === messageId)
    ?? (rootMessage.value?.id === messageId ? rootMessage.value : null)
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
    showError(t('chat.message.reactionFailed'))
  }
}

async function onPin(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    await togglePin(messageId, !msg.isPinned)
    msg.isPinned = !msg.isPinned
    showSuccess(msg.isPinned ? t('chat.message.pinned') : t('chat.message.unpinned'))
  } catch {
    showError(t('chat.message.pinFailed'))
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
    showSuccess(t('chat.message.deleteSuccess'))
  } catch {
    showError(t('chat.message.deleteFailed'))
  }
}

// --- 本人の投稿の編集（CMP-261105-1507） ---
const editingMessageId = ref<number | null>(null)
const editingBody = ref('')
const editSaving = ref(false)
const showEditDialog = computed({
  get: () => editingMessageId.value !== null,
  set: (open: boolean) => {
    if (!open) editingMessageId.value = null
  },
})

function onEdit(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  editingBody.value = msg.body ?? ''
  editingMessageId.value = messageId
}

async function onEditSave(body: string) {
  const messageId = editingMessageId.value
  if (messageId === null) return
  editSaving.value = true
  try {
    const res = await editMessage(messageId, body)
    const idx = messages.value.findIndex((m) => m.id === messageId)
    if (idx !== -1) messages.value[idx] = res.data
    editingMessageId.value = null
    showSuccess(t('chat.message.editSuccess'))
  } catch {
    showError(t('chat.message.editFailed'))
  } finally {
    editSaving.value = false
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
    showError(t('chat.message.bookmarkFailed'))
  }
}

watch(() => props.rootMessageId, () => {
  messages.value = []
  rootMessage.value = null
  nextCursor.value = null
  hasMore.value = false
  loadThread()
}, { immediate: true })
</script>

<template>
  <div class="flex h-full flex-col" data-testid="chat-thread-panel">
    <!-- ヘッダー -->
    <div class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-700">
      <span class="text-sm font-semibold">{{ $t('chat.thread.reply') }}</span>
      <Button icon="pi pi-times" text rounded size="small" severity="secondary" @click="emit('close')" />
    </div>

    <!-- ルートメッセージ -->
    <div v-if="rootMessage" class="border-b border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-800">
      <span class="text-xs font-semibold text-surface-600 dark:text-surface-400">{{ rootMessage.sender?.displayName || $t('chat.message.unknownSender') }}</span>
      <p class="mt-0.5 text-sm text-surface-700 dark:text-surface-300">{{ rootMessage.body }}</p>
      <span class="mt-1 text-xs text-surface-400">
        {{ $t('chat.thread.replyCount', { count: rootMessage.replyCount }) }}
      </span>
    </div>

    <!-- スレッドメッセージ一覧 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="loading && messages.length === 0" class="flex justify-center py-6">
        <LoadingBounce />
      </div>

      <div
        v-for="msg in messages"
        :key="msg.id"
        data-testid="thread-message"
      >
        <ChatMessageBubble
          :message="msg"
          :can-pin="false"
          :can-delete="canDelete"
          :is-mine="isMine(msg)"
          @reaction="onReaction"
          @pin="onPin"
          @delete="onDelete"
          @edit="onEdit"
          @bookmark="onBookmark"
        />
      </div>

      <div v-if="hasMore" class="flex justify-center py-2">
        <Button
          :label="$t('label.loadMore')"
          text
          size="small"
          :loading="loading"
          @click="loadThread(nextCursor!)"
        />
      </div>
    </div>

    <!-- 返信入力 -->
    <div class="border-t border-surface-200 p-3 dark:border-surface-700">
      <div class="flex gap-2">
        <InputText
          v-model="replyBody"
          :placeholder="$t('chat.thread.reply')"
          class="flex-1 text-sm"
          data-testid="thread-reply-input"
          @keydown.enter.prevent="sendReply"
        />
        <Button
          icon="pi pi-send"
          size="small"
          :loading="replySending"
          :disabled="!replyBody.trim()"
          data-testid="thread-reply-send"
          @click="sendReply"
        />
      </div>
    </div>

    <ChatMessageEditDialog
      v-model:visible="showEditDialog"
      :initial-body="editingBody"
      :saving="editSaving"
      @save="onEditSave"
    />
  </div>
</template>
