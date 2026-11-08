<script setup lang="ts">
import type { BroadcastTargetAudience } from '~/types/announcement_broadcast'
import { formatAudienceLabel } from '~/utils/broadcastAudience'

/**
 * F01.2.1 8-E: 送信履歴の宛先表示（AC-H17）。
 *
 * 「宛先: 平成20年度以前（送信時 12 チーム）」と、表示判定が現在の所属で行われる旨の注記を出す。
 * グループ名・チーム数は送信時の記録（targetAudience）なので、後でグループを改名・削除しても変わらない。
 * 宛先を絞っていない告知（audience が null）では何も描画しない。
 */
const props = defineProps<{
  audience: BroadcastTargetAudience | null | undefined
}>()

const { t } = useI18n()

const label = computed(() => (props.audience ? formatAudienceLabel(props.audience, t) : ''))
</script>

<template>
  <div v-if="audience" class="flex flex-col gap-1">
    <p class="text-sm font-medium text-surface-700 dark:text-surface-300" data-testid="broadcast-audience-summary">
      {{ t('announcement.audience_summary', { label, count: audience.teamCount ?? 0 }) }}
    </p>
    <p class="text-xs text-surface-500" data-testid="broadcast-audience-display-note">
      {{ t('announcement.dynamic_display_note') }}
    </p>
  </div>
</template>
