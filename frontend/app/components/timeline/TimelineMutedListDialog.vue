<script setup lang="ts">
/**
 * 非表示（ミュート）中のチーム/組織一覧ダイアログ（CMP-058）。
 *
 * <p>行ごとに「表示に戻す」で解除する。ミュートは個人フィードにのみ効く表示設定であり、
 * 解除してもしなくても投稿詳細は開ける。</p>
 *
 * <p>BE の `MuteResponse` は種別と ID しか返さないため、表示名は「ミュートした時点で
 * 親が控えた名前」（{@link nameResolver}）があればそれを使い、無ければ種別ラベル + ID を出す。
 * 名前が分からないことを空欄で誤魔化さず、必ず識別できる文字列を出す。</p>
 */
import type { TimelineMute } from '~/types/timeline'

const props = defineProps<{
  visible: boolean
  mutes: TimelineMute[]
  loading?: boolean
  /** ミュート対象の表示名を解決する関数（不明なら null を返す）。 */
  nameResolver?: (mute: TimelineMute) => string | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  unmute: [mute: TimelineMute]
}>()

const { t } = useI18n()

function displayName(mute: TimelineMute): string {
  const resolved = props.nameResolver?.(mute)
  if (resolved) return resolved
  return t('timeline.mute.unknownTarget', {
    type: t(`timeline.mute.targetType.${mute.mutedType}`),
    id: mute.mutedId,
  })
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="t('timeline.mute.dialogTitle')"
    :style="{ width: '420px' }"
    :breakpoints="{ '480px': '95vw' }"
    data-testid="timeline-muted-list-dialog"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="loading" class="flex justify-center py-6">
      <i class="pi pi-spin pi-spinner text-surface-400" />
    </div>

    <p
      v-else-if="mutes.length === 0"
      class="py-4 text-center text-sm text-surface-400 dark:text-surface-300"
    >
      {{ t('timeline.mute.emptyList') }}
    </p>

    <ul v-else class="flex flex-col divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="mute in mutes"
        :key="`${mute.mutedType}-${mute.mutedId}`"
        class="flex items-center justify-between gap-2 py-2"
        data-testid="timeline-muted-list-row"
      >
        <span class="min-w-0 flex-1 truncate text-sm text-surface-700 dark:text-surface-200">
          {{ displayName(mute) }}
        </span>
        <Button
          :label="t('timeline.mute.unmuteAction')"
          text
          size="small"
          data-testid="timeline-unmute-button"
          @click="emit('unmute', mute)"
        />
      </li>
    </ul>
  </Dialog>
</template>
