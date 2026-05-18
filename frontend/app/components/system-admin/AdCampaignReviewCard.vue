<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 審査キュー内の 1 キャンペーン要約カード。
 *
 * <p>{@link AdReviewQueueItem} を 1 件分受け取り、ステータス・自動フラグの理由・
 * 広告主アカウント・提出時刻を要約表示する。クリックで詳細ページへ遷移する。</p>
 */
import type { AdReviewQueueItem } from '~/types/adModeration'

interface Props {
  item: AdReviewQueueItem
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'open', campaignId: string): void
}>()

const { t } = useI18n()

const formattedSubmittedAt = computed(() => {
  if (!props.item.createdAt) return ''
  // 日時の整形は既存 useFormat があるが、ここではブラウザ Intl を直接使用
  const d = new Date(props.item.createdAt)
  if (Number.isNaN(d.getTime())) return props.item.createdAt
  return d.toLocaleString()
})

const moderationStatusSeverity = computed<'info' | 'warn' | 'secondary'>(() => {
  switch (props.item.moderationStatus) {
    case 'AUTO_FLAGGED':
      return 'warn'
    case 'AUTO_PASSED':
      return 'info'
    default:
      return 'secondary'
  }
})

function handleOpen() {
  emit('open', props.item.campaignId)
}
</script>

<template>
  <article
    class="rounded-lg border border-surface-200 bg-white p-4 shadow-sm transition hover:border-primary-300 hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
    :data-testid="`ad-review-card-${item.campaignId}`"
  >
    <header class="mb-3 flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <h3 class="truncate text-base font-semibold text-surface-900 dark:text-surface-50">
          {{ item.name }}
        </h3>
        <p class="mt-1 text-xs text-surface-500">
          {{ t('advertising.pages.system_admin_moderation.card_advertiser') }}:
          {{ item.advertiserAccountId }}
        </p>
      </div>
      <Tag
        :value="t(`advertising.moderation_status.${item.moderationStatus.toLowerCase()}`)"
        :severity="moderationStatusSeverity"
      />
    </header>

    <div
      v-if="item.autoFlagReason"
      class="mb-3 rounded bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-200"
    >
      {{ item.autoFlagReason }}
    </div>

    <footer class="flex items-center justify-between gap-3">
      <p class="text-xs text-surface-500">
        {{ t('advertising.pages.system_admin_moderation.card_submitted_at') }}:
        <span class="font-medium text-surface-700 dark:text-surface-200">
          {{ formattedSubmittedAt }}
        </span>
      </p>
      <Button
        size="small"
        :label="t('advertising.actions.view_detail')"
        icon="pi pi-arrow-right"
        icon-pos="right"
        @click="handleOpen"
      />
    </footer>
  </article>
</template>
