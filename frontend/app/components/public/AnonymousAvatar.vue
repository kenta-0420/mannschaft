<script setup lang="ts">
/**
 * F19.1 汎用 SVG アバター。
 *
 * 未ログイン / 非メンバー向けに「個人特定情報なし」のアバターを描画する。
 * BackendDTO の {@code avatarUrl} が null または anonymous マーカーの場合に使用。
 */
const props = withDefaults(
  defineProps<{
    size?: number
    alt?: string
  }>(),
  {
    size: 40,
    alt: '',
  },
)

const computedAlt = computed(() => props.alt)
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center justify-center rounded-full bg-surface-200 text-surface-500 dark:bg-surface-700 dark:text-surface-300"
    :style="{ width: `${size}px`, height: `${size}px` }"
    role="img"
    :aria-label="computedAlt"
  >
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="currentColor"
      :width="size * 0.65"
      :height="size * 0.65"
      aria-hidden="true"
    >
      <path
        d="M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z"
      />
    </svg>
  </span>
</template>
