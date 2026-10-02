<script setup lang="ts">
import type { GlobalNavItem } from '~/types/nav'

const props = defineProps<{
  item: GlobalNavItem
  /** レール（68px折りたたみ）表示中か。true でラベル非表示・アイコンのみ＋ホバーツールチップ */
  rail: boolean
  /** 現在ルートに一致しているか（GlobalSidebar が判定して渡す） */
  active: boolean
}>()

const { t } = useI18n()
const label = computed(() => t(props.item.labelKey, props.item.labelKey))
const isAdmin = computed(() => props.item.variant === 'admin')
const badgeText = computed(() => {
  const count = props.item.badgeCount
  if (!count) return null
  return count > 99 ? '99+' : String(count)
})
</script>

<template>
  <div class="group relative">
    <NuxtLink
      :to="item.path"
      class="relative flex items-center gap-3 rounded-lg py-2 text-sm font-medium transition-colors"
      :class="[
        rail ? 'justify-center px-0 py-2.5' : 'px-3',
        active
          ? (isAdmin ? 'bg-red-100 text-red-600 dark:bg-red-900/40 dark:text-red-400 font-semibold' : 'bg-primary/10 text-primary font-semibold')
          : (isAdmin ? 'text-red-500 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30' : 'text-surface-600 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800'),
      ]"
      :aria-label="label"
    >
      <!-- active下線（左端3pxバー） -->
      <span
        v-if="active"
        class="absolute left-0 top-2 bottom-2 w-[3px] rounded-full"
        :class="isAdmin ? 'bg-red-500' : 'bg-primary'"
      />
      <i :class="item.icon" class="shrink-0 text-base" />
      <span v-if="!rail" class="min-w-0 flex-1 truncate">{{ label }}</span>
      <!-- ワイド時: 件数ピル -->
      <span
        v-if="!rail && badgeText"
        class="ml-auto min-w-[18px] shrink-0 rounded-full bg-red-500 px-1.5 py-0.5 text-center text-[10px] font-bold leading-none text-white"
      >
        {{ badgeText }}
      </span>
      <!-- レール時: バッジdot化 -->
      <span
        v-if="rail && badgeText"
        class="absolute right-2 top-1 h-2 w-2 rounded-full bg-red-500 ring-2 ring-surface-0 dark:ring-surface-900"
      />
    </NuxtLink>

    <!-- レール時ツールチップ（ホバーで表示） -->
    <div
      v-if="rail"
      class="pointer-events-none absolute left-full top-1/2 z-40 ml-2 -translate-y-1/2 scale-95 whitespace-nowrap rounded-md bg-surface-900 px-2.5 py-1.5 text-xs font-semibold text-white opacity-0 shadow-lg transition-[opacity,transform] duration-150 group-hover:scale-100 group-hover:opacity-100 dark:bg-surface-100 dark:text-surface-900 motion-reduce:transition-none"
    >
      {{ label }}
    </div>
  </div>
</template>
