<script setup lang="ts">
// F02.9 Phase 2 — お気に入り 1 件分のカード。
// AVAILABLE / UNAVAILABLE で UI を切替え、削除以外のアクションは UNAVAILABLE 時に隠す。
import type { UserFavoriteItem } from '~/types/favorite'

interface Props {
  item: UserFavoriteItem
}

const props = defineProps<Props>()

const emit = defineEmits<{
  open: []
  edit: []
  remove: []
}>()

const { t } = useI18n()

const isAvailable = computed(() => props.item.entity.status === 'AVAILABLE')
// canEdit は UNAVAILABLE のときに編集ボタンを出さないために status と AND を取る
const showEdit = computed(() => isAvailable.value && props.item.entity.canEdit)

// アイコンが無い場合に表示するイニシャル。entityType の頭文字を使用。
const iconInitial = computed(() => props.item.entityType.charAt(0))
</script>

<template>
  <div
    class="flex items-center gap-3 p-3 border-2 border-surface-300 dark:border-surface-600 rounded-lg mb-2 bg-surface-0 dark:bg-surface-800"
    :class="{ 'opacity-60': !isAvailable }"
    :data-testid="`favorite-card-${item.favoriteId}`"
  >
    <span
      class="drag-handle cursor-grab select-none text-surface-400 hover:text-surface-600 dark:hover:text-surface-200"
      :aria-label="t('favorites.dragHandle')"
      role="button"
    >
      <i class="pi pi-bars" />
    </span>

    <img
      v-if="item.entity.iconUrl"
      :src="item.entity.iconUrl"
      class="w-10 h-10 rounded object-cover shrink-0"
      alt=""
    >
    <div
      v-else
      class="w-10 h-10 rounded bg-surface-200 dark:bg-surface-700 flex items-center justify-center text-surface-600 dark:text-surface-300 font-semibold shrink-0"
      aria-hidden="true"
    >
      {{ iconInitial }}
    </div>

    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2">
        <span class="font-bold text-sm text-surface-800 dark:text-surface-100 truncate">
          {{ item.entity.name }}
        </span>
        <span
          class="text-xs px-2 py-0.5 bg-surface-100 dark:bg-surface-700 text-surface-600 dark:text-surface-300 rounded shrink-0"
        >
          {{ t(`favorites.entityType.${item.entityType}`) }}
        </span>
      </div>
      <p
        v-if="isAvailable && item.entity.description"
        class="text-xs text-surface-500 dark:text-surface-400 truncate"
      >
        {{ item.entity.description }}
      </p>
      <p
        v-else-if="!isAvailable"
        class="text-xs text-surface-500 dark:text-surface-400"
      >
        {{ t('favorites.unavailable') }}
      </p>
    </div>

    <div class="flex gap-1 shrink-0">
      <Button
        v-if="isAvailable"
        type="button"
        :label="t('favorites.open')"
        size="small"
        text
        :aria-label="`${t('favorites.open')} ${item.entity.name}`"
        @click="emit('open')"
      />
      <Button
        v-if="showEdit"
        type="button"
        icon="pi pi-pencil"
        size="small"
        text
        :aria-label="`${t('favorites.edit')} ${item.entity.name}`"
        @click="emit('edit')"
      />
      <Button
        type="button"
        icon="pi pi-times"
        size="small"
        text
        severity="danger"
        :aria-label="`${t('favorites.remove')} ${item.entity.name}`"
        @click="emit('remove')"
      />
    </div>
  </div>
</template>
