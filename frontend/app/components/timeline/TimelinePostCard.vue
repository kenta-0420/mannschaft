<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'
import { CONTENT_TRUNCATE_LENGTH } from '~/types/timeline'

const props = withDefaults(defineProps<{
  post: TimelinePostResponse
  canPin?: boolean
  canDeleteOthers?: boolean
  /**
   * カード本体クリックで返信アコーディオンを開閉する（フィード用）。
   * false の場合はレガシー動作（clickPost emit）となり、アコーディオンUIは描画しない。
   * 返信アイテム（1段）や投稿詳細ページのカードでは false を渡してネストを防ぐ。
   */
  repliesAccordion?: boolean
}>(), {
  repliesAccordion: true,
})

const emit = defineEmits<{
  reply: [postId: number]
  bookmark: [postId: number]
  pin: [postId: number]
  delete: [postId: number]
  repost: [postId: number]
  clickPost: [postId: number]
  mitayoToggled: [postId: number, mitayo: boolean, mitayoCount: number]
  replyAdded: [postId: number]
}>()

const { t } = useI18n()
const { relativeTime } = useRelativeTime()
const { addReaction, removeReaction, getReplies, createReply } = useTimelineApi()
const { showError } = useNotification()

/**
 * 投稿元バッジ。個人集約タイムライン（所属 team/org 横断）で BE が scope.name を enrich したときのみ表示する。
 * 単一スコープの TL では scope.name が付与されないため出ない（非劣化）。
 * slug があれば該当スコープページへ遷移するリンクにする。
 */
const sourceBadge = computed(() => {
  const scope = props.post.scope
  if (!scope?.name) return null
  let to: string | null = null
  if (scope.slug) {
    if (scope.scopeType === 'TEAM') to = `/teams/${scope.slug}`
    else if (scope.scopeType === 'ORGANIZATION') to = `/organizations/${scope.slug}`
  }
  return { name: scope.name, to }
})
const menu = ref()
const expanded = ref(false)
const mitayoLoading = ref(false)

/**
 * 村行事のシステム自動投稿か（F17.2 Wave2 §3.9(c)）。
 * `user`/`postedAs` とも null なので、ここで分岐しないと「名無し・空アバター」の
 * 空カードになる（設計書 §3.9 冒頭の警告）。
 */
const isSystemPost = computed(() => !!props.post.systemPostType)

/** システム投稿種別ごとの PrimeIcons アイコン（固定アイコン・§3.9(c)）。 */
function systemPostIcon(type: string | null | undefined): string {
  switch (type) {
    case 'EVENT_CREATED':
      return 'pi pi-calendar-plus'
    case 'EVENT_UPCOMING':
      return 'pi pi-bell'
    case 'MEETUP_CONFIRMED':
      return 'pi pi-check-circle'
    case 'FESTIVAL_STARTED':
      return 'pi pi-star-fill'
    default:
      return 'pi pi-megaphone'
  }
}

/** システム投稿種別ラベル（§14 `village.systemPost.*` テンプレキー）。表示名の下に添える種別バッジ用。 */
function systemPostTypeI18nKey(type: string | null | undefined): string | null {
  switch (type) {
    case 'EVENT_CREATED':
      return 'village.systemPost.eventCreated'
    case 'EVENT_UPCOMING':
      return 'village.systemPost.eventUpcoming'
    case 'MEETUP_CONFIRMED':
      return 'village.systemPost.meetupConfirmed'
    case 'FESTIVAL_STARTED':
      return 'village.systemPost.festivalStarted'
    default:
      return null
  }
}

const displayName = computed(() => {
  if (isSystemPost.value) return t('village.systemPost.authorName')
  if (props.post.postedAs) {
    return props.post.postedAs.displayName || props.post.postedAs.name || ''
  }
  return props.post.user?.displayName || ''
})

/** システム投稿は固定画像を持たないため常に null（テンプレート側で `systemPostIcon` を使う）。 */
const avatarUrl = computed(() => {
  if (isSystemPost.value) return null
  if (props.post.postedAs) {
    return props.post.postedAs.avatarUrl || props.post.postedAs.logoUrl || null
  }
  return props.post.user?.avatarUrl || null
})

const systemPostBadgeLabel = computed(() => {
  if (!isSystemPost.value) return null
  const key = systemPostTypeI18nKey(props.post.systemPostType)
  return key ? t(key) : null
})

const displayContent = computed(() => {
  if (!props.post.content?.content) return ''
  if (expanded.value || !props.post.isTruncated) return props.post.content.content
  return props.post.content.content.substring(0, CONTENT_TRUNCATE_LENGTH)
})

const menuItems = computed(() => {
  const items = []
  if (props.canPin) {
    items.push({
      label: props.post.content?.isPinned ? 'ピン解除' : 'ピン留め',
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

async function handleToggleMitayo() {
  if (mitayoLoading.value) return
  mitayoLoading.value = true
  try {
    const result = props.post.mitayo
      ? await removeReaction(props.post.id)
      : await addReaction(props.post.id)
    if (result?.data) {
      emit('mitayoToggled', props.post.id, result.data.mitayo, result.data.mitayoCount)
    }
  }
  finally {
    mitayoLoading.value = false
  }
}

// --- 返信アコーディオン ---
const repliesExpanded = ref(false)
const replies = ref<TimelinePostResponse[]>([])
const repliesLoading = ref(false)
const repliesLoaded = ref(false)
const replyCursor = ref<number | null>(null)
const repliesHasNext = ref(false)
const replyContent = ref('')
const replySubmitting = ref(false)
/**
 * 初回ロード（一覧を丸ごと代入する loadReplies）の in-flight promise。
 * これが解決する前に返信送信で楽観追加すると、後着の代入で追加分が消えるため、
 * submitReply はこの promise を待ってから push する（競合根治）。
 */
let repliesLoadPromise: Promise<void> | null = null

async function loadReplies() {
  repliesLoading.value = true
  const promise = (async () => {
    try {
      const res = await getReplies(props.post.id)
      replies.value = res.data.posts
      replyCursor.value = res.meta.nextCursor
      repliesHasNext.value = res.meta.hasNext
      repliesLoaded.value = true
    } catch {
      showError(t('timeline.postError'))
    } finally {
      repliesLoading.value = false
    }
  })()
  repliesLoadPromise = promise
  try {
    await promise
  } finally {
    if (repliesLoadPromise === promise) repliesLoadPromise = null
  }
}

async function loadMoreReplies() {
  if (!replyCursor.value || repliesLoading.value) return
  repliesLoading.value = true
  try {
    const res = await getReplies(props.post.id, replyCursor.value)
    replies.value.push(...res.data.posts)
    replyCursor.value = res.meta.nextCursor
    repliesHasNext.value = res.meta.hasNext
  } catch {
    showError(t('timeline.postError'))
  } finally {
    repliesLoading.value = false
  }
}

/** カード本体クリック。フィードではアコーディオン開閉、レガシー（詳細/返信）では clickPost emit。 */
function onCardClick() {
  if (props.repliesAccordion) {
    void toggleReplies()
  } else {
    emit('clickPost', props.post.id)
  }
}

async function toggleReplies() {
  repliesExpanded.value = !repliesExpanded.value
  if (repliesExpanded.value && !repliesLoaded.value) {
    await loadReplies()
  }
}

/** 返信ボタン。アコーディオン有効時は開閉トグル、無効時はレガシー emit。 */
async function onReplyButton() {
  if (props.repliesAccordion) {
    await toggleReplies()
  } else {
    emit('reply', props.post.id)
  }
}

async function submitReply() {
  const content = replyContent.value.trim()
  if (!content || replySubmitting.value) return
  replySubmitting.value = true
  try {
    // 初回ロードが in-flight なら先に確定させ、後着の一覧代入で楽観追加が消えるのを防ぐ。
    if (repliesLoadPromise) await repliesLoadPromise
    const res = await createReply(props.post.id, content)
    // 会話は古い順のため末尾に追加。返信数の +1 は親（shared ref）へ委譲（prop 直接変更を避ける）。
    replies.value.push(res.data)
    emit('replyAdded', props.post.id)
    repliesLoaded.value = true
    replyContent.value = ''
  } catch {
    showError(t('timeline.postError'))
  } finally {
    replySubmitting.value = false
  }
}

function replyDisplayName(r: TimelinePostResponse): string {
  if (r.systemPostType) return t('village.systemPost.authorName')
  if (r.postedAs) return r.postedAs.displayName || r.postedAs.name || ''
  return r.user?.displayName || ''
}

function replyAvatar(r: TimelinePostResponse): string | null {
  if (r.systemPostType) return null
  if (r.postedAs) return r.postedAs.avatarUrl || r.postedAs.logoUrl || null
  return r.user?.avatarUrl || null
}

function replyIsSystemPost(r: TimelinePostResponse): boolean {
  return !!r.systemPostType
}
</script>

<template>
  <div
    class="cursor-pointer rounded-xl border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-sm dark:bg-surface-800"
    data-testid="team-timeline-post"
    @click="onCardClick"
  >
    <!-- 投稿元バッジ（個人集約タイムラインのみ・scope.name enrich 時に表示） -->
    <div v-if="sourceBadge" class="mb-2">
      <NuxtLink
        v-if="sourceBadge.to"
        :to="sourceBadge.to"
        class="inline-flex items-center gap-1 rounded-full bg-surface-100 px-2 py-0.5 text-xs font-medium text-surface-600 hover:bg-surface-200 dark:bg-surface-700 dark:text-surface-200"
        :aria-label="`${t('timeline.postSource')}: ${sourceBadge.name}`"
        data-testid="timeline-post-source"
        @click.stop
      >
        <i class="pi pi-sitemap text-[10px]" />
        <span>{{ sourceBadge.name }}</span>
      </NuxtLink>
      <span
        v-else
        class="inline-flex items-center gap-1 rounded-full bg-surface-100 px-2 py-0.5 text-xs font-medium text-surface-600 dark:bg-surface-700 dark:text-surface-200"
        :aria-label="`${t('timeline.postSource')}: ${sourceBadge.name}`"
        data-testid="timeline-post-source"
      >
        <i class="pi pi-sitemap text-[10px]" />
        <span>{{ sourceBadge.name }}</span>
      </span>
    </div>

    <!-- ピン表示 -->
    <div v-if="post.content?.isPinned" class="mb-2 flex items-center gap-1 text-xs text-surface-400 dark:text-surface-300">
      <i class="pi pi-thumbtack" />
      <span>ピン留め</span>
    </div>

    <!-- ヘッダー -->
    <div class="mb-2 flex items-start justify-between">
      <div class="flex items-center gap-3">
        <Avatar
          :image="avatarUrl || undefined"
          :icon="!avatarUrl && isSystemPost ? systemPostIcon(post.systemPostType) : undefined"
          :label="!avatarUrl && !isSystemPost ? displayName.charAt(0) : undefined"
          shape="circle"
          size="normal"
          data-testid="timeline-system-post-avatar"
        />
        <div>
          <div class="flex items-center gap-2">
            <span class="text-sm font-semibold" data-testid="timeline-post-author-name">{{ displayName }}</span>
            <span v-if="post.postedAs?.handle" class="text-xs text-surface-400 dark:text-surface-300">
              {{ post.postedAs.handle }}
            </span>
            <span
              v-if="systemPostBadgeLabel"
              class="inline-flex items-center rounded-full bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-950 dark:text-primary-300"
              data-testid="timeline-system-post-badge"
            >
              {{ systemPostBadgeLabel }}
            </span>
          </div>
          <div class="flex items-center gap-1 text-xs text-surface-400 dark:text-surface-300">
            <NuxtLink
              :to="`/timeline/${post.id}`"
              class="hover:underline"
              data-testid="timeline-post-permalink"
              @click.stop
            >
              {{ relativeTime(post.audit?.createdAt) }}
            </NuxtLink>
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
        data-testid="team-timeline-post-menu"
        @click.stop="toggleMenu"
      />
      <Menu ref="menu" :model="menuItems" popup />
    </div>

    <!-- リポスト元 -->
    <div
      v-if="post.repostOf"
      class="mb-2 rounded-lg border border-surface-100 bg-surface-50 p-3 text-sm dark:border-surface-700 dark:bg-surface-900"
    >
      <template v-if="post.repostOf.deleted">
        <span class="text-surface-400 dark:text-surface-300">元の投稿は削除されました</span>
      </template>
      <template v-else>
        <div class="mb-1 flex items-center gap-2 text-xs text-surface-400 dark:text-surface-300">
          <i class="pi pi-replay" />
          <span>{{ post.repostOf.user?.displayName }}</span>
        </div>
        <p class="text-surface-600 dark:text-surface-300">{{ post.repostOf.contentPreview }}</p>
      </template>
    </div>

    <!-- 本文 -->
    <div v-if="post.content?.content" class="mb-3">
      <p class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700 dark:text-surface-100">
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
      v-if="(post.attachments?.length ?? 0) > 0"
      class="mb-3 grid gap-2"
      :class="post.attachments!.length === 1 ? 'grid-cols-1' : 'grid-cols-2'"
    >
      <template v-for="att in post.attachments!" :key="att.id">
        <img
          v-if="att.attachmentType === 'IMAGE'"
          :src="att.image?.thumbnailUrl || att.image?.url"
          class="w-full rounded-lg object-cover"
          :class="post.attachments.length === 1 ? 'max-h-96' : 'h-48'"
          loading="lazy"
          @click.stop
        >
        <div
          v-else-if="att.attachmentType === 'VIDEO_FILE' && att.file?.fileKey"
          @click.stop
        >
          <VideoPlayer
            :file-key="att.file.fileKey"
            :thumbnail-url="att.video?.videoThumbnailUrl"
            :processing-status="att.video?.videoProcessingStatus"
            :mime-type="att.file?.mimeType"
          />
        </div>
        <a
          v-else-if="att.attachmentType === 'VIDEO_LINK'"
          :href="att.video?.videoUrl"
          target="_blank"
          rel="noopener"
          class="flex items-center gap-2 rounded-lg border border-surface-300 p-3"
          @click.stop
        >
          <img v-if="att.video?.videoThumbnailUrl" :src="att.video.videoThumbnailUrl" class="h-16 w-24 rounded object-cover" >
          <span class="text-sm text-primary">{{ att.video?.videoTitle || '動画を見る' }}</span>
        </a>
        <a
          v-else-if="att.attachmentType === 'LINK_PREVIEW' && att.link?.linkUrl"
          :href="att.link.linkUrl"
          target="_blank"
          rel="noopener"
          class="flex gap-3 rounded-lg border border-surface-300 p-3"
          @click.stop
        >
          <img v-if="att.link?.ogImageUrl" :src="att.link.ogImageUrl" class="h-16 w-16 rounded object-cover" >
          <div class="min-w-0">
            <p class="truncate text-sm font-medium">{{ att.link?.ogTitle }}</p>
            <p class="truncate text-xs text-surface-400 dark:text-surface-300">{{ att.link?.ogSiteName }}</p>
          </div>
        </a>
      </template>
    </div>

    <!-- 投票 -->
    <div v-if="post.poll" class="mb-3 rounded-lg border border-surface-300 p-3" @click.stop>
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
            <span class="text-xs text-surface-400 dark:text-surface-300">{{ opt.voteCount }}票</span>
          </div>
        </div>
      </div>
      <p class="mt-2 text-xs text-surface-400 dark:text-surface-300">
        {{ post.poll.totalVoteCount }}票
        <span v-if="post.poll.isClosed"> ・終了</span>
        <span v-else-if="post.poll.expiresAt"> ・{{ relativeTime(post.poll.expiresAt) }}まで</span>
      </p>
    </div>

    <!-- みたよ！ボタン -->
    <div class="mb-2" @click.stop>
      <TimelineMitayoButton
        :mitayo="post.mitayo"
        :mitayo-count="post.mitayoCount"
        :loading="mitayoLoading"
        data-testid="team-timeline-like"
        @toggle="handleToggleMitayo"
      />
    </div>

    <!-- アクションバー（各ボタンはヒット領域44x44。アイコン/文字の視覚サイズはtext-xsのまま維持） -->
    <div class="flex items-center gap-2 border-t border-surface-100 pt-2" @click.stop>
      <button
        class="flex min-h-11 min-w-11 items-center justify-center gap-1 text-xs text-surface-400 transition-colors hover:text-primary dark:text-surface-300"
        data-testid="team-timeline-reply-btn"
        @click="onReplyButton"
      >
        <i class="pi pi-comment" />
        <span v-if="post.stats?.replyCount">{{ post.stats.replyCount }}</span>
      </button>
      <button
        class="flex min-h-11 min-w-11 items-center justify-center gap-1 text-xs transition-colors hover:text-green-500"
        :class="(post.stats?.repostCount ?? 0) > 0 ? 'text-green-500' : 'text-surface-400 dark:text-surface-300'"
        @click="emit('repost', post.id)"
      >
        <i class="pi pi-replay" />
        <span v-if="post.stats?.repostCount">{{ post.stats.repostCount }}</span>
      </button>
      <button
        class="flex min-h-11 min-w-11 items-center justify-center gap-1 text-xs transition-colors hover:text-amber-500"
        :class="post.isBookmarked ? 'text-amber-500' : 'text-surface-400 dark:text-surface-300'"
        @click="emit('bookmark', post.id)"
      >
        <i :class="post.isBookmarked ? 'pi pi-bookmark-fill' : 'pi pi-bookmark'" />
      </button>
    </div>

    <!-- 返信アコーディオン（フィード用・repliesAccordion=true のときのみ） -->
    <div
      v-if="repliesAccordion && repliesExpanded"
      class="mt-3 border-t border-surface-100 pt-3 dark:border-surface-700"
      data-testid="timeline-replies-accordion"
      @click.stop
    >
      <!-- インライン返信フォーム -->
      <div class="mb-3">
        <Textarea
          v-model="replyContent"
          :placeholder="t('timeline.writeReply')"
          auto-resize
          rows="2"
          class="w-full"
          data-testid="team-timeline-comment-input"
        />
        <div class="mt-2 flex justify-end">
          <Button
            :label="t('timeline.replySubmit')"
            size="small"
            :loading="replySubmitting"
            :disabled="!replyContent.trim()"
            data-testid="team-timeline-comment-submit"
            @click="submitReply"
          />
        </div>
      </div>

      <!-- ローディング -->
      <div v-if="repliesLoading && !repliesLoaded" class="flex justify-center py-4">
        <i class="pi pi-spin pi-spinner text-surface-400" />
      </div>

      <!-- 0件 -->
      <p
        v-else-if="repliesLoaded && replies.length === 0"
        class="py-2 text-center text-sm text-surface-400 dark:text-surface-300"
      >
        {{ t('timeline.noReplies') }}
      </p>

      <!-- 返信一覧（1段のみ・簡易行） -->
      <div v-else class="flex flex-col gap-3">
        <div v-for="reply in replies" :key="reply.id" class="flex gap-2">
          <Avatar
            :image="replyAvatar(reply) || undefined"
            :icon="!replyAvatar(reply) && replyIsSystemPost(reply) ? systemPostIcon(reply.systemPostType) : undefined"
            :label="!replyAvatar(reply) && !replyIsSystemPost(reply) ? replyDisplayName(reply).charAt(0) : undefined"
            shape="circle"
            size="normal"
          />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="text-sm font-semibold">{{ replyDisplayName(reply) }}</span>
              <span class="text-xs text-surface-400 dark:text-surface-300">
                {{ relativeTime(reply.audit?.createdAt) }}
              </span>
            </div>
            <p class="whitespace-pre-wrap text-sm leading-relaxed text-surface-700 dark:text-surface-100">
              {{ reply.content?.content }}
            </p>
          </div>
        </div>
        <Button
          v-if="repliesHasNext"
          :label="t('timeline.loadMoreReplies')"
          text
          size="small"
          :loading="repliesLoading"
          @click="loadMoreReplies"
        />
      </div>
    </div>
  </div>
</template>
