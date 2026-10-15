<script setup lang="ts">
import type { ActionMemoCategory, AvailableTeam } from '~/types/actionMemo'

/**
 * F02.5 Phase 3: チーム投稿先選択コンポーネント。
 *
 * <p>category が WORK 以外の場合は非表示。
 * チームが1件の場合は自動選択、2件以上はドロップダウンで選択。</p>
 */

const props = defineProps<{
  category: ActionMemoCategory
  availableTeams: AvailableTeam[]
  modelValue: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()

const isVisible = computed(() => props.category === 'WORK')

// チームが1件なら自動選択
watch(
  () => props.availableTeams,
  (teams) => {
    if (props.category !== 'WORK') return
    if (teams.length === 1 && props.modelValue === null) {
      emit('update:modelValue', teams[0]!.id)
    }
  },
  { immediate: true },
)

watch(
  () => props.category,
  (cat) => {
    if (cat !== 'WORK') {
      // WORK 以外になったら投稿先をクリア
      emit('update:modelValue', null)
    } else if (props.availableTeams.length === 1) {
      emit('update:modelValue', props.availableTeams[0]!.id)
    }
  },
)

function onSelectChange(event: Event) {
  const val = (event.target as HTMLSelectElement).value
  emit('update:modelValue', val === '' ? null : Number(val))
}
</script>

<template>
  <div
    v-show="isVisible"
    class="flex flex-col gap-1.5"
    data-testid="team-post-switch"
  >
    <label class="text-xs font-medium text-surface-600 dark:text-surface-300">
      {{ t('action_memo.phase3.post_to_team.button') }}
    </label>

    <!-- チームなし -->
    <p
      v-if="availableTeams.length === 0"
      class="text-xs text-surface-400 dark:text-surface-500"
      data-testid="team-post-switch-no-teams"
    >
      {{ t('action_memo.phase3.post_to_team.no_teams') }}
    </p>

    <!-- チーム1件: 自動選択済み表示 -->
    <div
      v-else-if="availableTeams.length === 1"
      class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-1.5 text-sm dark:border-surface-700 dark:bg-surface-800"
      data-testid="team-post-switch-single"
    >
      <i class="pi pi-users text-xs text-primary" />
      <span class="text-surface-700 dark:text-surface-200">{{ availableTeams[0]!.name }}</span>
      <i class="pi pi-check ml-auto text-xs text-emerald-500" />
    </div>

    <!-- チーム2件以上: ドロップダウン -->
    <select
      v-else
      :value="modelValue ?? ''"
      :class="[
        'rounded-lg border px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:bg-transparent dark:text-surface-100',
        modelValue === null
          ? 'border-primary font-medium text-primary dark:border-primary'
          : 'border-surface-200 text-surface-700 dark:border-surface-700 dark:text-surface-200',
      ]"
      data-testid="team-post-switch-select"
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
  </div>
</template>
