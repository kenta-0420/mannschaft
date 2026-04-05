<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'
import { CONTENT_TRUNCATE_LENGTH } from '~/types/timeline'

const props = defineProps<{
  post: TimelinePostResponse
  canPin?: boolean
  canDeleteOthers?: boolean
}>()

const emit = defineEmits<{
  reply: [postId: number]
  reaction: [postId: number, emoji: string]
  bookmark: [postId: number]
  pin: [postId: number]
  delete: [postId: number]
  repost: [postId: number]
  clickPost: [postId: number]
}>()

const { relativeTime } = useRelativeTime()
const menu = ref()
const expanded = ref(false)

const displayName = computed(() => {
  if (props.post.postedAs) {
    return props.post.postedAs.displayName || props.post.postedAs.name || ''
  }
  return props.post.user?.displayName || ''
})

const avatarUrl = computed(() => {
  if (props.post.postedAs) {
    return props.post.postedAs.avatarUrl || props.post.postedAs.logoUrl || null
  }
  return props.post.user?.avatarUrl || null
})

const displayContent = computed(() => {
  if (!props.post.content) return ''
  if (expanded.value || !props.post.isTruncated) return props.post.content
  return props.post.content.substring(0, CONTENT_TRUNCATE_LENGTH)
})

const menuItems = computed(() => {
  const items = []
  if (props.canPin) {
    items.push({
      label: props.post.isPinned ? 'ピン解除' : 'ピン留め',
      icon: 'pi pi-thumbtack',
      command: () => emit('pin', props.post.id),
    })
  }
  if (props.canDeleteOthers) {
    items.push({
      label: '削除',
      icon: 'pi pi-trash',
      command: () => emit('delete', props.post.id),
    })
  }
  return items
})

function toggleMenu(event: Event) {
  menu.value.toggle(event)
}
</script>

<template>
  <div
    class="cursor-pointer rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-sm"
    @click="emit('clickPost', post.id)"
  >
    <!-- ピン表示 -->
    <div v-if="post.isPinned" class="mb-2 flex items-center gap-1 text-xs text-surface-400">
      <i class="pi pi-thumbtack" />
      <span>ピン留め</span>
    </div>

    <!-- ヘッダー -->
    <div class="mb-2 flex items-start justify-between">
      <div class="flex items-center gap-3">
        <Avatar
          :image="avatarUrl || undefined"
          :label="avatarUrl ? undefined : displayName.charAt(0)"
          shape="circle"
          size="normal"
        />
        <div>
          <div class="flex items-center gap-2">
            <span class="text-sm font-semibold">{{ displayName }}</span>
            <span v-if="post.postedAs?.handle" class="text-xs text-surface-400">
              {{ post.postedAs.handle }}
            </span>
          </div>
          <div class="flex items-center gap-1 text-xs text-surface-400">
            <span>{{ relativeTime(post.createdAt) }}</span>
            <span v-if="post.isEdited" class="text-surface-300">・編集済み</span>
          </div>
        </div>
      </div>
      <Button
        v-if="menuItems.length > 0"
        icon="pi pi-ellipsis-h"
        text
        rounded
        severity="secondary"
        size="small"
        @click.stop="toggleMenu"
      />
      <Menu ref="menu" :model="menuItems" popup />
    </div>

    <!-- リポスト元 -->
    <div
      v-if="post.repostOf"
      class="mb-2 rounded-lg border border-surface-100 bg-surface-50 p-3 text-sm"
    >
      <template v-if="post.repostOf.deleted">
        <span class="text-surface-400">元の投稿は削除されました</span>
      </template>
      <template v-else>
        <div class="mb-1 flex items-center gap-2 text-xs text-surface-400">
          <i class="pi pi-replay" />
          <span>{{ post.repostOf.user?.displayName }}</span>
        </div>
        <p class="text-surface-600">{{ post.repostOf.contentPreview }}</p>
      </template>
    </div>

    <!-- 本文 -->
    <div v-if="post.content" class="mb-3">
      <p class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700">
        {{ displayContent }}
      </p>
      <button
        v-if="post.isTruncated && !expanded"
        class="mt-1 text-sm font-medium text-primary hover:underline"
        @click.stop="expanded = true"
      >
        続きを読む
      </button>
    </div>

    <!-- 添付ファイル -->
    <div
      v-if="post.attachments.length > 0"
      class="mb-3 grid gap-2"
      :class="post.attachments.length === 1 ? 'grid-cols-1' : 'grid-cols-2'"
    >
      <template v-for="att in post.attachments" :key="att.id">
        <img
          v-if="att.attachmentType === 'IMAGE'"
          :src="att.thumbnailUrl || att.url"
          class="w-full rounded-lg object-cover"
          :class="post.attachments.length === 1 ? 'max-h-96' : 'h-48'"
          loading="lazy"
          @click.stop
        />
        <a
          v-else-if="att.attachmentType === 'VIDEO_LINK'"
          :href="att.videoUrl"
          target="_blank"
          rel="noopener"
          class="flex items-center gap-2 rounded-lg border border-surface-300 p-3"
          @click.stop
        >
          <img v-if="att.videoThumbnailUrl" :src="att.videoThumbnailUrl" class="h-16 w-24 rounded object-cover" />
          <span class="text-sm text-primary">{{ att.videoTitle || '動画を見る' }}</span>
        </a>
        <a
          v-else-if="att.attachmentType === 'LINK_PREVIEW' && att.linkUrl"
          :href="att.linkUrl"
          target="_blank"
          rel="noopener"
          class="flex gap-3 rounded-lg border border-surface-300 p-3"
          @click.stop
        >
          <img v-if="att.ogImageUrl" :src="att.ogImageUrl" class="h-16 w-16 rounded object-cover" />
          <div class="min-w-0">
            <p class="truncate text-sm font-medium">{{ att.ogTitle }}</p>
            <p class="truncate text-xs text-surface-400">{{ att.ogSiteName }}</p>
          </div>
        </a>
      </template>
    </div>

    <!-- 投票 -->
    <div v-if="post.poll" class="mb-3 rounded-lg border border-surface-300 p-3">
      <p class="mb-2 text-sm font-medium">{{ post.poll.question }}</p>
      <div class="flex flex-col gap-2">
        <div
          v-for="opt in post.poll.options"
          :key="opt.id"
          class="relative overflow-hidden rounded-md border px-3 py-2 text-sm"
          :class="post.poll.myVoteOptionId === opt.id
            ? 'border-primary bg-primary/5'
            : 'border-surface-200'"
        >
          <div
            class="absolute inset-y-0 left-0 bg-primary/10"
            :style="{ width: post.poll.totalVoteCount ? `${(opt.voteCount / post.poll.totalVoteCount) * 100}%` : '0%' }"
          />
          <div class="relative flex items-center justify-between">
            <span>{{ opt.optionText }}</span>
            <span class="text-xs text-surface-400">{{ opt.voteCount }}票</span>
          </div>
        </div>
      </div>
      <p class="mt-2 text-xs text-surface-400">
        {{ post.poll.totalVoteCount }}票
        <span v-if="post.poll.isClosed"> ・終了</span>
        <span v-else-if="post.poll.expiresAt"> ・{{ relativeTime(post.poll.expiresAt) }}まで</span>
      </p>
    </div>

    <!-- リアクション -->
    <div class="mb-2" @click.stop>
      <TimelineReactionPicker
        :reaction-summary="post.reactionSummary"
        :my-reactions="post.myReactions"
        @toggle="(emoji) => emit('reaction', post.id, emoji)"
      />
    </div>

    <!-- アクションバー -->
    <div class="flex items-center gap-4 border-t border-surface-100 pt-2" @click.stop>
      <button
        class="flex items-center gap-1 text-xs text-surface-400 transition-colors hover:text-primary"
        @click="emit('reply', post.id)"
      >
        <i class="pi pi-comment" />
        <span v-if="post.replyCount">{{ post.replyCount }}</span>
      </button>
      <button
        class="flex items-center gap-1 text-xs transition-colors hover:text-green-500"
        :class="post.repostCount > 0 ? 'text-green-500' : 'text-surface-400'"
        @click="emit('repost', post.id)"
      >
        <i class="pi pi-replay" />
        <span v-if="post.repostCount">{{ post.repostCount }}</span>
      </button>
      <button
        class="flex items-center gap-1 text-xs transition-colors hover:text-amber-500"
        :class="post.isBookmarked ? 'text-amber-500' : 'text-surface-400'"
        @click="emit('bookmark', post.id)"
      >
        <i :class="post.isBookmarked ? 'pi pi-bookmark-fill' : 'pi pi-bookmark'" />
      </button>
    </div>
  </div>
</template>
