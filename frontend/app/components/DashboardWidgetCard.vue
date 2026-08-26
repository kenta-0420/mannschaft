<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'

defineOptions({ inheritAttrs: false })

withDefaults(
  defineProps<{
    title?: string
    icon?: string
    loading?: boolean
    colSpan?: 1 | 2 | 3
    refreshable?: boolean
    isDragging?: boolean
    isDropTarget?: boolean
    /**
     * タイトルクリック時の遷移先。指定時はタイトル全体が NuxtLink になる。
     */
    to?: string | RouteLocationRaw
    /**
     * slot コンテンツ領域を縦スクロール可能にするか。デフォルト true。
     */
    scrollable?: boolean
    /**
     * scrollable=true 時のカード全体の最大高さ（CSS 値）。デフォルト '15rem'。
     *
     * この値は「リスト項目が約3件ちょうど見える」高さの基準。カードルートに max-height として
     * 付与することで、行内で最も中身の多いカード（ただしこの上限まで）が行の高さを決め、
     * 隣接カードは h-full で同じ高さまで自動で伸びて揃う。上限を超えた分は本文が枠内で縦スクロール。
     */
    maxHeight?: string
  }>(),
  {
    title: undefined,
    icon: undefined,
    colSpan: undefined,
    to: undefined,
    scrollable: true,
    maxHeight: '15rem',
  },
)

const emit = defineEmits<{
  refresh: []
}>()

const collapsed = ref(false)
</script>

<template>
  <div
    v-bind="$attrs"
    class="relative flex flex-col rounded-xl border bg-surface-0 p-4 shadow-[var(--shadow-card)] transition-all hover:shadow-[var(--shadow-card-hover)] focus-within:shadow-[var(--shadow-card-hover)] focus-within:-translate-y-0.5 dark:bg-surface-800"
    :class="{
      'col-span-1': !colSpan || colSpan === 1,
      'md:col-span-2': colSpan === 2,
      'md:col-span-3': colSpan === 3,
      'opacity-40 shadow-none': isDragging,
      'border-primary border-t-[3px]': isDropTarget,
      'border-surface-200 dark:border-surface-700': !isDropTarget,
      'h-full': scrollable,
    }"
    :style="scrollable ? { maxHeight } : undefined"
  >
    <!-- ドロップインジケーター線 -->
    <div
      v-if="isDropTarget"
      class="pointer-events-none absolute inset-x-0 top-0 h-[3px] rounded-t-xl bg-primary"
    />

    <!-- ヘッダー（固定・スクロールしない） -->
    <div
      v-if="title"
      class="flex flex-none items-center justify-between"
      :class="{ 'mb-3': !collapsed }"
    >
      <NuxtLink
        v-if="to"
        :to="to"
        class="group/title flex items-center gap-2.5 cursor-pointer"
      >
        <span
          v-if="icon"
          class="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover/title:bg-primary/15 dark:bg-primary/15"
        >
          <i :class="icon" class="text-sm" />
        </span>
        <h3
          class="text-sm font-semibold tracking-wide text-surface-600 transition-colors group-hover/title:text-primary dark:text-surface-300"
        >
          {{ title }}
        </h3>
        <i
          class="pi pi-external-link text-xs text-surface-400 opacity-0 transition-opacity group-hover/title:opacity-100"
        />
      </NuxtLink>
      <div v-else class="flex items-center gap-2.5">
        <span
          v-if="icon"
          class="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary dark:bg-primary/15"
        >
          <i :class="icon" class="text-sm" />
        </span>
        <h3 class="text-sm font-semibold tracking-wide text-surface-600 dark:text-surface-300">
          {{ title }}
        </h3>
      </div>
      <div class="flex items-center gap-1">
        <slot name="actions" />
        <Button
          v-if="refreshable"
          icon="pi pi-refresh"
          text
          rounded
          size="small"
          :loading="loading"
          @click="emit('refresh')"
        />
        <Button
          :icon="collapsed ? 'pi pi-plus' : 'pi pi-minus'"
          text
          rounded
          size="small"
          :aria-label="collapsed ? 'ウィジェットを展開する' : 'ウィジェットを折り畳む'"
          @click="collapsed = !collapsed"
        />
      </div>
    </div>

    <!-- ローディング＋コンテンツ（折り畳み制御）。
         scrollable 時は flex-1 min-h-0 で余った縦を本文が埋め、溢れたら本文内でスクロールする。 -->
    <Transition name="widget-collapse">
      <div
        v-show="!collapsed"
        :class="scrollable ? 'flex min-h-0 flex-1 flex-col' : ''"
      >
        <!-- ローディング -->
        <div v-if="loading" class="space-y-3">
          <Skeleton height="1.5rem" />
          <Skeleton height="1.5rem" width="80%" />
          <Skeleton height="1.5rem" width="60%" />
        </div>

        <!-- コンテンツ（scrollable 時のみ枠内スクロール） -->
        <div
          v-else
          :class="scrollable ? 'min-h-0 flex-1 overflow-y-auto pr-1' : ''"
        >
          <slot />
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.widget-collapse-enter-active,
.widget-collapse-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.widget-collapse-enter-from,
.widget-collapse-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
