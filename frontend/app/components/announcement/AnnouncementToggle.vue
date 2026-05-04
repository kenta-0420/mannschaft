<script setup lang="ts">
/**
 * F02.6 投稿フォームに埋め込む「お知らせウィジェットに表示する」スイッチ。
 *
 * 使用方法:
 *   <AnnouncementAnnouncementToggle v-model="displayInAnnouncement" />
 *
 * オフラインの場合は disabled にし、ツールチップを表示する。
 */

const props = defineProps<{
  modelValue: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const { t } = useI18n()

// PWA オフライン検出（F11.1 useOfflineQueue パターン）
const isOnline = ref(true)
onMounted(() => {
  isOnline.value = navigator.onLine
  window.addEventListener('online', () => { isOnline.value = true })
  window.addEventListener('offline', () => { isOnline.value = false })
})

const isDisabled = computed(() => props.disabled || !isOnline.value)

const tooltipText = computed(() =>
  !isOnline.value ? 'オフラインのため設定できません' : undefined,
)

function handleChange(val: boolean | undefined) {
  emit('update:modelValue', val ?? false)
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <div class="flex items-center gap-2">
      <Checkbox
        v-tooltip="tooltipText"
        :model-value="modelValue"
        :disabled="isDisabled"
        binary
        input-id="displayInAnnouncement"
        @update:model-value="handleChange"
      />
      <label
        for="displayInAnnouncement"
        class="cursor-pointer select-none text-sm"
        :class="isDisabled ? 'text-surface-400' : 'text-surface-700 dark:text-surface-200'"
      >
        {{ t('announcement.display_in_announcement') }}
      </label>
    </div>
    <p class="ml-6 text-xs text-surface-400">
      {{ t('announcement.display_in_announcement_hint') }}
    </p>
  </div>
</template>
