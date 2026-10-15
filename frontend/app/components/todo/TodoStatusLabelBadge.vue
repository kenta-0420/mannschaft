<script setup lang="ts">
import {
  BUCKET_DEFAULT_COLOR,
  type TodoStatusLabel,
  type TodoStatusLabelBucket,
  type TodoStatusLabelInfo,
} from '~/types/todoStatusLabel'

/**
 * F02.3.1 — TODO ステータスラベル バッジ
 *
 * ラベル情報がある場合は label.color + label.name を表示。
 * label が null の場合は fallbackBucket（OPEN/IN_PROGRESS/COMPLETED）から
 * SYSTEM 既定の色 + 名前にフォールバック。
 * fallbackBucket も無い場合は「-」を表示。
 */
const props = defineProps<{
  label?: TodoStatusLabel | TodoStatusLabelInfo | null
  /** ラベル未設定時のフォールバック先 bucket（既存 TODO の status カラム） */
  fallbackBucket?: string | null
}>()

const { t } = useI18n()

const SYSTEM_DEFAULT_LABEL_KEYS: Record<TodoStatusLabelBucket, string> = {
  OPEN: 'todo.statusLabel.bucket.OPEN',
  IN_PROGRESS: 'todo.statusLabel.bucket.IN_PROGRESS',
  COMPLETED: 'todo.statusLabel.bucket.COMPLETED',
}

const isValidBucket = (v: unknown): v is TodoStatusLabelBucket =>
  v === 'OPEN' || v === 'IN_PROGRESS' || v === 'COMPLETED'

const display = computed(() => {
  if (props.label) {
    const bucket = props.label.bucket
    return {
      name: props.label.name,
      color: props.label.color ?? BUCKET_DEFAULT_COLOR[bucket],
    }
  }
  if (isValidBucket(props.fallbackBucket)) {
    return {
      name: t(SYSTEM_DEFAULT_LABEL_KEYS[props.fallbackBucket]),
      color: BUCKET_DEFAULT_COLOR[props.fallbackBucket],
    }
  }
  return null
})
</script>

<template>
  <span
    v-if="display"
    class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium"
    :style="{
      backgroundColor: `${display.color}1A`,
      color: display.color,
      border: `1px solid ${display.color}66`,
    }"
  >
    <span
      class="inline-block h-2 w-2 rounded-full"
      :style="{ backgroundColor: display.color }"
    />
    {{ display.name }}
  </span>
  <span v-else class="text-xs text-surface-400">-</span>
</template>
