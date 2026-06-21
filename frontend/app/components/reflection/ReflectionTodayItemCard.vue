<script setup lang="ts">
/**
 * F06.5 今日ビューの 1 item カード（§4.3）。
 *
 * コマ由来 item（slotKind 非 null）と自由テーマ由来 item を共通描画する。
 * - themeId 有り：当日エントリの作成/編集導線（記入済みバッジ／想起待ちバッジ）。
 * - themeId 無し（空きコマ）：テーマ作成導線（AC-17）。
 */
import type { ReflectionTodayItem } from '~/types/reflection'

const props = defineProps<{
  item: ReflectionTodayItem
}>()

const emit = defineEmits<{
  open: [item: ReflectionTodayItem]
  createTheme: [item: ReflectionTodayItem]
}>()

const { t } = useI18n()

const hasTheme = computed(() => !!props.item.themeId)
</script>

<template>
  <div class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800">
    <div class="min-w-0 flex-1">
      <div class="flex items-center gap-2">
        <span v-if="item.periodLabel" class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-700">
          {{ item.periodLabel }}
        </span>
        <span class="truncate text-sm font-medium">{{ item.subjectName || t('reflection.today.no_theme') }}</span>
      </div>
      <div class="mt-1 flex items-center gap-2">
        <span v-if="item.hasEntryToday" class="inline-flex items-center gap-1 text-xs text-green-600">
          <i class="pi pi-check-circle" />{{ t('reflection.today.has_entry') }}
        </span>
        <span v-if="item.isMasked" class="inline-flex items-center gap-1 text-xs text-amber-600">
          <i class="pi pi-eye-slash" />{{ t('reflection.today.masked_badge') }}
        </span>
      </div>
    </div>

    <div class="flex-shrink-0">
      <Button
        v-if="hasTheme"
        :label="item.hasEntryToday ? t('reflection.today.edit_entry') : t('reflection.today.create_entry')"
        :icon="item.hasEntryToday ? 'pi pi-pencil' : 'pi pi-plus'"
        size="small"
        :severity="item.hasEntryToday ? 'secondary' : undefined"
        :outlined="item.hasEntryToday"
        @click="emit('open', item)"
      />
      <Button
        v-else
        :label="t('reflection.today.create_theme_for_slot')"
        icon="pi pi-plus"
        size="small"
        severity="secondary"
        outlined
        @click="emit('createTheme', item)"
      />
    </div>
  </div>
</template>
