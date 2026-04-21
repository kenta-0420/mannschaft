<script setup lang="ts">
import type { ChatMessageResponse } from '~/types/chat'

/** チャット用プリセット絵文字（タイムラインとは独立して定義） */
const CHAT_PRESET_EMOJIS = ['👍', '👏', '🙏', '😊', '❤️', '🔥', '🙇'] as const

const props = defineProps<{
  message: ChatMessageResponse
  canPin?: boolean
  canDelete?: boolean
}>()

const emit = defineEmits<{
  reply: [messageId: number]
  reaction: [messageId: number, emoji: string]
  pin: [messageId: number]
  delete: [messageId: number]
  bookmark: [messageId: number]
}>()

const { relativeTime } = useRelativeTime()
const showActions = ref(false)
const showEmojiPicker = ref(false)

function toggleReaction(emoji: string) {
  emit('reaction', props.message.id, emoji)
  showEmojiPicker.value = false
}
</script>

<template>
  <div
    class="group relative px-4 py-1.5 transition-colors hover:bg-surface-50"
    @mouseenter="showActions = true"
    @mouseleave="showActions = false; showEmojiPicker = false"
  >
    <!-- システムメッセージ -->
    <div v-if="message.isSystem" class="py-1 text-center text-xs text-surface-400">
      {{ message.body }}
    </div>

    <!-- 削除済み -->
    <div v-else-if="message.isDeleted" class="py-1 text-sm italic text-surface-400">
      このメッセージは削除されました
    </div>

    <!-- 通常メッセージ -->
    <template v-else>
      <div class="flex gap-3">
        <Avatar
          :label="message.sender?.displayName?.charAt(0) || '?'"
          shape="circle"
          size="normal"
          class="mt-0.5 shrink-0"
        />
        <div class="min-w-0 flex-1">
          <div class="flex items-baseline gap-2">
            <span class="text-sm font-semibold">{{ message.sender?.displayName || '不明' }}</span>
            <span class="text-xs text-surface-400">{{ relativeTime(message.createdAt) }}</span>
            <span v-if="message.isEdited" class="text-xs text-surface-300">(編集済み)</span>
            <i v-if="message.isPinned" class="pi pi-thumbtack text-xs text-amber-500" />
          </div>

          <!-- 転送元 -->
          <div v-if="message.forwardedFrom" class="mb-1 rounded border-l-2 border-surface-300 bg-surface-50 px-2 py-1 text-xs text-surface-500">
            <span class="font-medium">{{ message.forwardedFrom.sender?.displayName }}</span>
            <span v-if="message.forwardedFrom.channelName"> in #{{ message.forwardedFrom.channelName }}</span>
            <p class="mt-0.5">{{ message.forwardedFrom.body }}</p>
          </div>

          <!-- 本文 -->
          <p class="whitespace-pre-wrap text-sm leading-relaxed">{{ message.body }}</p>

          <!-- 添付ファイル -->
          <div v-if="message.attachments.length > 0" class="mt-1 flex flex-wrap gap-2">
            <a
              v-for="att in message.attachments"
              :key="att.id"
              :href="att.url"
              target="_blank"
              rel="noopener"
              class="inline-flex items-center gap-1 rounded border border-surface-300 px-2 py-1 text-xs text-primary hover:bg-surface-50"
            >
              <i class="pi pi-file" />
              {{ att.fileName }}
            </a>
          </div>

          <!-- リアクション -->
          <div v-if="Object.keys(message.reactionSummary).length > 0" class="mt-1 flex flex-wrap gap-1">
            <button
              v-for="(count, emoji) in message.reactionSummary"
              :key="emoji"
              class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs"
              :class="message.myReactions.includes(String(emoji))
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-surface-200'"
              @click="toggleReaction(String(emoji))"
            >
              {{ emoji }} {{ count }}
            </button>
          </div>

          <!-- スレッド返信 -->
          <button
            v-if="message.replyCount > 0 && !message.parentId"
            class="mt-1 text-xs font-medium text-primary hover:underline"
            @click="emit('reply', message.id)"
          >
            {{ message.replyCount }}件の返信
          </button>
        </div>
      </div>

      <!-- ホバーアクション -->
      <div
        v-if="showActions"
        class="absolute -top-3 right-4 flex items-center gap-0.5 rounded-md border border-surface-300 bg-surface-0 shadow-sm"
      >
        <button
          class="p-1.5 text-surface-400 hover:text-surface-600"
          title="リアクション"
          @click="showEmojiPicker = !showEmojiPicker"
        >
          <i class="pi pi-face-smile text-xs" />
        </button>
        <button
          v-if="!message.parentId"
          class="p-1.5 text-surface-400 hover:text-surface-600"
          title="スレッド返信"
          @click="emit('reply', message.id)"
        >
          <i class="pi pi-reply text-xs" />
        </button>
        <button
          class="p-1.5 text-surface-400 hover:text-surface-600"
          title="ブックマーク"
          @click="emit('bookmark', message.id)"
        >
          <i :class="message.isBookmarked ? 'pi pi-bookmark-fill text-amber-500' : 'pi pi-bookmark'" class="text-xs" />
        </button>
        <button
          v-if="canPin"
          class="p-1.5 text-surface-400 hover:text-surface-600"
          title="ピン留め"
          @click="emit('pin', message.id)"
        >
          <i class="pi pi-thumbtack text-xs" />
        </button>
        <button
          v-if="canDelete"
          class="p-1.5 text-surface-400 hover:text-red-500"
          title="削除"
          @click="emit('delete', message.id)"
        >
          <i class="pi pi-trash text-xs" />
        </button>
      </div>

      <!-- 絵文字ピッカー -->
      <div
        v-if="showEmojiPicker"
        class="absolute -top-10 right-4 z-20 flex gap-1 rounded-lg border border-surface-300 bg-surface-0 p-2 shadow-lg"
      >
        <button
          v-for="emoji in CHAT_PRESET_EMOJIS"
          :key="emoji"
          class="flex h-7 w-7 items-center justify-center rounded text-base hover:bg-surface-100"
          @click="toggleReaction(emoji)"
        >
          {{ emoji }}
        </button>
      </div>
    </template>
  </div>
</template>
