<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type {
  ScopeWidgetPreviewDetail,
  ScopeWidgetPreviewField,
  ScopeWidgetPreviewState,
  ScopeWidgetPreviewTarget,
} from '~/composables/useScopeWidgetPreview'
import { useDatetime } from '~/composables/useDatetime'
import { useMarkdownRenderer } from '~/composables/useMarkdownRenderer'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

const props = defineProps<{
  state: ScopeWidgetPreviewState
  target: ScopeWidgetPreviewTarget | null
  detail: ScopeWidgetPreviewDetail | null
  error: unknown
  trigger: HTMLElement | null
}>()
const emit = defineEmits<{ close: []; retry: [] }>()
const { t } = useI18n()
const { formatDate, formatDateTime } = useDatetime()
const { renderMarkdown } = useMarkdownRenderer()
const title = computed(() => (props.state === 'FULL' ? props.detail?.title : props.target?.title))
const body = computed(() => {
  if (props.state !== 'FULL' || !props.detail) return ''
  if (props.detail.bodyFormat === 'markdown') return renderMarkdown(props.detail.body)
  if (props.detail.bodyFormat === 'html') return sanitizeHtml(props.detail.body)
  return ''
})
function displayField(field: ScopeWidgetPreviewField): string {
  if (field.format === 'date') return formatDate(String(field.value))
  if (field.format === 'datetime') return formatDateTime(String(field.value))
  if (field.format === 'translation') return t(String(field.value))
  return String(field.value)
}
function restoreFocus(): void {
  if (props.trigger?.isConnected) props.trigger.focus({ preventScroll: true })
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
    :header="title || t('swipeWidgets.preview.title')"
    :close-button-props="{
      class: 'min-h-11 min-w-11',
      'aria-label': t('swipeWidgets.preview.close'),
    }"
    :style="{ width: 'min(48rem, calc(100vw - 2rem))', maxHeight: 'calc(100dvh - 2rem)' }"
    :breakpoints="{ '767px': 'calc(100vw - 1rem)' }"
    :pt="{
      content: { class: 'min-w-0 min-h-0 overflow-auto' },
      header: { class: 'min-w-0 gap-2' },
      title: { class: 'min-w-0 break-words' },
    }"
    @update:visible="
      (value) => {
        if (!value) emit('close')
      }
    "
    @after-hide="restoreFocus"
  >
    <div data-testid="scope-widget-preview" aria-live="polite" :aria-busy="state === 'LOADING'">
      <PageLoading v-if="state === 'LOADING'" />
      <DashboardErrorState
        v-else-if="state === 'ERROR' || state === 'UNAVAILABLE'"
        :error="error"
        :title="t('swipeWidgets.preview.loadFailed')"
        :show-retry="state === 'ERROR'"
        @retry="emit('retry')"
      />
      <article v-else-if="state === 'FULL' && detail" class="min-w-0 space-y-4">
        <dl v-if="detail.fields.length" class="space-y-3">
          <div v-for="field in detail.fields" :key="field.label" class="min-w-0">
            <dt class="text-sm text-surface-500">{{ t(`swipeWidgets.preview.${field.label}`) }}</dt>
            <dd class="whitespace-pre-wrap break-words">{{ displayField(field) }}</dd>
          </div>
        </dl>
        <div
          v-if="detail.bodyFormat === 'text' && detail.body"
          class="whitespace-pre-wrap break-words"
          data-testid="scope-widget-preview-body"
        >
          {{ detail.body }}
        </div>
        <!-- 本文は既存Markdown renderer / DOMPurifyを通した結果だけを描画する。 -->
        <!-- eslint-disable-next-line vue/no-v-html -- サニタイズ済み本文のみ -->
        <div
          v-else-if="body"
          class="scope-preview-body article-body min-w-0 break-words"
          data-testid="scope-widget-preview-body"
          v-html="body"
        />
        <p
          v-else-if="target && !['calendar', 'schedule'].includes(target.kind)"
          class="text-surface-500"
        >
          {{ t('swipeWidgets.preview.emptyBody') }}
        </p>
      </article>
    </div>
    <template #footer>
      <div class="flex w-full flex-wrap justify-end gap-2">
        <Button
          autofocus
          :label="t('swipeWidgets.preview.close')"
          severity="secondary"
          class="min-h-11"
          data-testid="scope-widget-preview-close"
          @click="emit('close')"
        />
        <NuxtLink
          v-if="state === 'FULL' && detail"
          :to="detail.route"
          class="inline-flex min-h-11 items-center rounded bg-primary px-4 text-primary-contrast"
          data-testid="scope-widget-preview-source"
          @click="emit('close')"
        >
          {{ t('swipeWidgets.preview.openSource') }}
        </NuxtLink>
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.scope-preview-body :deep(img) {
  max-width: 100%;
  height: auto;
}
.scope-preview-body :deep(pre),
.scope-preview-body :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
}
</style>
