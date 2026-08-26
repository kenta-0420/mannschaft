<script setup lang="ts">
import type { InboxLabel } from '~/types/inbox'

/**
 * F04.11 Phase 2 — インボックスラベルチップ。
 *
 * label の color を背景色として色付きチップを表示する。
 * removable=true の場合は × ボタンで解除イベントを emit する。
 * 手本: TodoStatusLabelBadge.vue
 */
const props = defineProps<{
  label: InboxLabel
  /** × で解除ボタンを表示するか。 */
  removable?: boolean
}>()

const emit = defineEmits<{
  remove: [label: InboxLabel]
}>()

const { t } = useI18n()

/** チップの表示色（color が null の場合はデフォルト色）。 */
const chipColor = computed(() => props.label.color ?? '#94a3b8')
</script>

<template>
  <span
    class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium"
    :style="{
      backgroundColor: `${chipColor}22`,
      color: chipColor,
      border: `1px solid ${chipColor}66`,
    }"
    :data-testid="`inbox-label-chip-${label.id}`"
  >
    <i
      v-if="label.icon"
      :class="label.icon"
      class="text-[0.65rem]"
    />
    <span class="inline-block h-2 w-2 rounded-full" :style="{ backgroundColor: chipColor }" />
    <span>{{ label.name }}</span>
    <button
      v-if="removable"
      type="button"
      class="ml-0.5 rounded-full p-0.5 hover:bg-black/10"
      :aria-label="`${t('inbox.label.removed')} ${label.name}`"
      :data-testid="`inbox-label-chip-remove-${label.id}`"
      @click.stop="emit('remove', label)"
    >
      <i class="pi pi-times text-[0.55rem]" />
    </button>
  </span>
</template>
