<script setup lang="ts">
import type { AnnouncementPreviewResponse, AnnouncementPreviewState } from '~/composables/useAnnouncementPreview'
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMarkdownRenderer } from '~/composables/useMarkdownRenderer'
import { useBulletinApi } from '~/composables/useBulletinApi'
import { useErrorHandler } from '~/composables/useErrorHandler'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

const props = defineProps<{
  state: AnnouncementPreviewState
  preview: AnnouncementPreviewResponse | null
  itemTitle: string
  error?: unknown
  trigger?: HTMLElement | null
}>()
const emit = defineEmits<{ close: []; retry: []; displayed: [] }>()
const { t } = useI18n()
const { renderMarkdown } = useMarkdownRenderer()
const { getAttachmentDownloadUrl } = useBulletinApi()
const { handleApiError } = useErrorHandler()
const downloading = ref<number | null>(null)
const imageFailed = ref(false)
let active = true
onScopeDispose(() => { active = false })
let savedScroll: { element: HTMLElement; top: number; left: number }[] = []
let savedWindow: { x: number; y: number } | null = null
let originList: HTMLElement | null = null
let originIndex = 0

const body = computed(() => {
  if (props.state !== 'FULL' || !props.preview) return ''
  if (props.preview.sourceType === 'BLOG_POST') return renderMarkdown(props.preview.blogPost?.content?.body ?? '')
  return sanitizeHtml(props.preview.bulletinThread?.body ?? '')
})
const title = computed(() => {
  if (props.state === 'FULL' && props.preview) {
    const sourceTitle = props.preview.sourceType === 'BLOG_POST'
      ? props.preview.blogPost?.content?.title
      : props.preview.bulletinThread?.title
    if (sourceTitle) return sourceTitle
  }
  return props.itemTitle || t('announcement.preview.title')
})
const cover = computed(() => props.state === 'FULL' ? props.preview?.blogPost?.content?.coverImageUrl : null)

watch(() => props.preview, async (value) => {
  imageFailed.value = false
  downloading.value = null
  if (!value || props.state !== 'FULL') return
  await nextTick()
  if (props.preview === value && props.state === 'FULL') emit('displayed')
})

function savePosition(): void {
  savedWindow = { x: window.scrollX, y: window.scrollY }
  savedScroll = []
  originList = props.trigger?.closest<HTMLElement>('[data-announcement-list]') ?? null
  originIndex = originList && props.trigger
    ? [...originList.querySelectorAll('[data-announcement-item]')].indexOf(props.trigger)
    : 0
  let element = props.trigger?.parentElement
  while (element) {
    if (element.scrollHeight > element.clientHeight || element.scrollWidth > element.clientWidth) {
      savedScroll.push({ element, top: element.scrollTop, left: element.scrollLeft })
    }
    element = element.parentElement
  }
}

function restorePosition(): void {
  for (const position of savedScroll) {
    position.element.scrollTop = position.top
    position.element.scrollLeft = position.left
  }
  if (savedWindow) window.scrollTo(savedWindow.x, savedWindow.y)
  const original = props.trigger
  const remaining = originList?.querySelectorAll<HTMLElement>('[data-announcement-item]')
  const fallback = remaining?.[Math.max(0, Math.min(originIndex, remaining.length - 1))]
    ?? originList?.querySelector<HTMLElement>('[data-announcement-heading]')
  ;(original?.isConnected ? original : fallback)?.focus({ preventScroll: true })
}

function onImageError(event: Event): void {
  if (event.target instanceof HTMLImageElement && event.target.isConnected) imageFailed.value = true
}

async function download(id: number | undefined): Promise<void> {
  if (!id || downloading.value !== null) return
  const content = props.preview
  downloading.value = id
  try {
    const result = await getAttachmentDownloadUrl(id)
    if (!active || props.state !== 'FULL' || props.preview !== content) return
    window.open(result.downloadUrl, '_blank', 'noopener,noreferrer')
  }
  catch (error) {
    if (active && props.preview === content) handleApiError(error)
  }
  finally {
    if (props.preview === content) downloading.value = null
  }
}
</script>

<template>
  <Dialog
    :visible="state !== 'CLOSED'"
    modal
    block-scroll
    dismissable-mask
    close-on-escape
    :draggable="false"
    :close-button-props="{ class: 'min-h-11 min-w-11', 'aria-label': t('announcement.preview.close') }"
    :header="title"
    :style="{ width: 'min(56rem, calc(100vw - 2rem))', maxHeight: 'calc(100dvh - 2rem)' }"
    :breakpoints="{ '767px': 'calc(100vw - 1rem)' }"
    :pt="{ root: { class: 'announcement-preview-dialog' }, content: { class: 'min-h-0 overflow-auto min-w-0' }, header: { class: 'shrink-0 min-w-0 gap-2' }, title: { class: 'min-w-0 break-words' }, headerActions: { class: 'shrink-0' }, footer: { class: 'shrink-0' } }"
    @update:visible="value => { if (!value) emit('close') }"
    @show="savePosition"
    @after-hide="restorePosition"
  >
    <div data-testid="announcement-preview" aria-live="polite" :aria-busy="state === 'LOADING'">
      <PageLoading v-if="state === 'LOADING'" />
      <DashboardErrorState
        v-else-if="state === 'ERROR'"
        :error="error"
        :title="t('announcement.preview.load_failed')"
        @retry="emit('retry')"
      />
      <DashboardErrorState
        v-else-if="state === 'UNAVAILABLE'"
        kind="notFoundOrForbidden"
        :message="t('announcement.no_longer_available')"
        :show-retry="false"
      />
      <div v-else-if="state === 'LOCKED'" class="py-8 text-center text-surface-700 dark:text-surface-200" data-testid="announcement-preview-locked">
        <i class="pi pi-lock mr-2" aria-hidden="true" />{{ t('announcement.preview.locked') }}
      </div>
      <article v-else-if="state === 'FULL' && preview" class="min-w-0" @error.capture="onImageError">
        <img v-if="cover" :src="cover" :alt="title" class="mb-4 max-h-80 w-full rounded object-contain">
        <!-- 本文は既存 Markdown renderer/DOMPurify を通した client-only の取得結果。 -->
        <!-- eslint-disable-next-line vue/no-v-html -- サニタイズ済み本文のみ -->
        <div v-if="body" class="announcement-preview-body prose max-w-none break-words dark:prose-invert" v-html="body" />
        <p v-else class="py-4 text-surface-500" data-testid="announcement-preview-empty">{{ t('announcement.preview.empty_body') }}</p>
        <div v-if="imageFailed" role="status" class="mt-4">
          <p>{{ t('announcement.preview.image_failed') }}</p>
          <Button :label="t('announcement.preview.retry')" class="min-h-11" @click="emit('retry')" />
        </div>
        <ul v-if="preview.attachments.length" class="mt-4 space-y-2">
          <li v-for="attachment in preview.attachments" :key="attachment.id" class="flex min-w-0 items-center gap-2">
            <span class="min-w-0 flex-1 break-all">{{ attachment.originalFilename }}</span>
            <Button :label="t('bulletin.attachment.download')" icon="pi pi-download" class="min-h-11 shrink-0" :loading="downloading === attachment.id" @click="download(attachment.id)" />
          </li>
        </ul>
      </article>
    </div>
    <template #footer>
      <div class="flex w-full flex-wrap justify-end gap-2">
        <Button autofocus :label="t('announcement.preview.close')" severity="secondary" class="min-h-11" data-testid="announcement-preview-close" @click="emit('close')" />
        <NuxtLink v-if="state === 'FULL' && preview?.sourceUrl" :to="preview.sourceUrl" class="inline-flex min-h-11 items-center rounded bg-primary px-4 text-primary-contrast" data-testid="announcement-preview-source">
          {{ t('announcement.preview.open_source') }}
        </NuxtLink>
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.announcement-preview-body :deep(img) { max-width: 100%; height: auto; }
.announcement-preview-body :deep(pre),
.announcement-preview-body :deep(table) { display: block; max-width: 100%; overflow-x: auto; }
:global(.announcement-preview-dialog) { padding-bottom: env(safe-area-inset-bottom); }
@media (max-width: 767px) {
  :global(.announcement-preview-dialog) { max-height: calc(100dvh - 1rem) !important; }
}
</style>
