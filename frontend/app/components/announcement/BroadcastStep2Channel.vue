<script setup lang="ts">
import type { BroadcastChannel, WizardFormState } from '~/types/announcement_broadcast'

interface ChannelCard {
  key: BroadcastChannel
  icon: string
  labelKey: string
}

const props = defineProps<{
  modelValue: WizardFormState
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WizardFormState]
  back: []
  next: []
}>()

const { t } = useI18n()

const channels: ChannelCard[] = [
  { key: 'BULLETIN_THREAD', icon: 'pi pi-list', labelKey: 'announcement.channel_bulletin' },
  { key: 'TIMELINE_POST', icon: 'pi pi-comments', labelKey: 'announcement.channel_timeline' },
  { key: 'BLOG_POST', icon: 'pi pi-file-edit', labelKey: 'announcement.channel_blog' },
  { key: 'TODO', icon: 'pi pi-check-square', labelKey: 'announcement.channel_todo' },
  { key: 'SCHEDULE', icon: 'pi pi-calendar', labelKey: 'announcement.channel_schedule' },
  { key: 'SURVEY', icon: 'pi pi-chart-bar', labelKey: 'announcement.channel_survey' },
]

function isSelected(channel: BroadcastChannel) {
  return props.modelValue.selectedChannel === channel
}

function selectChannel(channel: BroadcastChannel) {
  emit('update:modelValue', { ...props.modelValue, selectedChannel: channel })
}

const canProceed = computed(() => props.modelValue.selectedChannel !== null)
</script>

<template>
  <div class="flex flex-col gap-6">
    <p class="text-sm text-surface-500 dark:text-surface-400">
      {{ t('announcement.step2_title') }}
    </p>

    <!-- チャネルカードグリッド -->
    <div class="grid grid-cols-3 gap-3">
      <button
        v-for="ch in channels"
        :key="ch.key"
        type="button"
        class="flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 p-4 transition-colors"
        :class="
          isSelected(ch.key)
            ? 'border-primary-500 bg-primary-50 text-primary-700 dark:border-primary-400 dark:bg-primary-900/20 dark:text-primary-300'
            : 'border-surface-200 bg-white text-surface-700 hover:border-primary-300 hover:bg-primary-50/50 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300 dark:hover:border-primary-600'
        "
        @click="selectChannel(ch.key)"
      >
        <i :class="[ch.icon, 'mb-2 text-2xl']" />
        <span class="text-center text-xs font-medium leading-tight">{{ t(ch.labelKey) }}</span>
      </button>
    </div>

    <!-- ナビゲーション -->
    <div class="flex justify-between pt-2">
      <Button :label="$t('button.back')" icon="pi pi-arrow-left" severity="secondary" text @click="emit('back')" />
      <Button
        :label="$t('button.next')"
        icon="pi pi-arrow-right"
        icon-pos="right"
        :disabled="!canProceed"
        @click="emit('next')"
      />
    </div>
  </div>
</template>
