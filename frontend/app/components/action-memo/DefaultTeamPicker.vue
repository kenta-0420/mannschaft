<script setup lang="ts">
import type { AvailableTeam } from '~/types/actionMemo'

/**
 * F02.5 Phase 3: デフォルト投稿先チーム選択コンポーネント（設定画面用）。
 *
 * <p>WORKカテゴリでメモを作成した際に自動選択されるチームを設定する。</p>
 */

const props = defineProps<{
  availableTeams: AvailableTeam[]
  modelValue: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()

function onSelectChange(event: Event) {
  const val = (event.target as HTMLSelectElement).value
  emit('update:modelValue', val === '' ? null : Number(val))
}
</script>

<template>
  <div class="flex flex-col gap-1.5" data-testid="default-team-picker">
    <label class="text-xs font-medium text-surface-600 dark:text-surface-300">
      {{ t('action_memo.phase3.settings.default_team') }}
    </label>
    <select
      :value="modelValue ?? ''"
      class="rounded-lg border border-surface-200 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700 dark:bg-transparent dark:text-surface-100"
      data-testid="default-team-picker-select"
      @change="onSelectChange"
    >
      <option value="">{{ t('action_memo.phase3.post_to_team.select_team') }}</option>
      <option
        v-for="team in availableTeams"
        :key="team.id"
        :value="team.id"
      >
        {{ team.name }}
      </option>
    </select>
    <p class="text-xs text-surface-400 dark:text-surface-500">
      {{ t('action_memo.phase3.settings.default_team_help') }}
    </p>
  </div>
</template>
