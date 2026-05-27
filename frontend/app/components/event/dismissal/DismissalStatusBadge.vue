<script setup lang="ts">
/**
 * F03.12 §16 解散通知ステータスバッジ。
 *
 * <p>イベント詳細で「解散済み」を表示する小さなバッジ。
 * - {@code status.dismissed === false}: 何も描画しない（呼び出し側の slot を消したい場合の判定用に v-if を返す形）
 * - {@code status.dismissed === true}: 「✓ 解散済み（YYYY-MM-DD HH:mm 送信）」
 * - {@code status.reminderCount >= 1}: ツールチップで「主催者にN回リマインド済み」</p>
 */
import dayjs from 'dayjs'
import type { DismissalStatusResponse } from '~/types/care'

const props = defineProps<{
  status: DismissalStatusResponse
}>()

const { userTimezone } = useDatetime()

/** 表示用にフォーマットした送信日時（YYYY-MM-DD HH:mm）。 */
const sentAtLabel = computed<string>(() => {
  const iso = props.status.dismissalNotificationSentAt
  if (!iso) return ''
  const d = dayjs(iso)
  if (!d.isValid()) return ''
  return d.tz(userTimezone.value).format('YYYY-MM-DD HH:mm')
})

const reminderTooltip = computed<string | null>(() => {
  if (props.status.reminderCount >= 1) {
    return `主催者に${props.status.reminderCount}回リマインド済み`
  }
  return null
})
</script>

<template>
  <span
    v-if="status.dismissed"
    v-tooltip.top="reminderTooltip ?? undefined"
    class="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900 dark:text-emerald-200"
    data-testid="dismissal-status-badge"
  >
    <i class="pi pi-check-circle text-xs" />
    <span>
      {{ $t('event.dismissal.dismissed_at', { sentAt: sentAtLabel }) }}
    </span>
    <span
      v-if="status.reminderCount >= 1"
      class="ml-1 rounded-full bg-amber-200 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800"
      data-testid="dismissal-reminder-count"
    >
      ×{{ status.reminderCount }}
    </span>
  </span>
</template>
