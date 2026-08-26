<script setup lang="ts">
/**
 * 和文の折返し制御用テキスト描画。
 * ロケールの「意味句単位のセグメント配列」を tm() で読み出し、
 * 各セグメントを inline-block で描画することで句の境界でのみ折り返す。
 * （t() は常に文字列を返すため配列は必ず tm() + rt() で解決する）
 */
const props = defineProps<{ path: string }>()

const { tm, rt } = useI18n()

const segments = computed(() => {
  const raw = tm(props.path)
  if (Array.isArray(raw)) return raw.map((seg) => rt(seg))
  return []
})
</script>

<template>
  <span>
    <span v-for="(seg, i) in segments" :key="i" class="inline-block">{{ seg }}</span>
  </span>
</template>
