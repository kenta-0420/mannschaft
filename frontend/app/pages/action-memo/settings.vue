<script setup lang="ts">
import type { ActionMemoCategory } from '~/types/actionMemo'

/**
 * F02.5 行動メモ設定画面。
 *
 * <p>Phase 1 では {@code mood_enabled} トグルのみ。
 * Phase 3 でデフォルト投稿先チームとデフォルトカテゴリを追加。
 * Phase 4-β でリマインド設定を追加。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

const moodEnabled = ref<boolean>(false)
const defaultPostTeamId = ref<number | null>(null)
const defaultCategory = ref<ActionMemoCategory>('OTHER')
const reminderEnabled = ref<boolean>(false)
const reminderTime = ref<string>('')

onMounted(async () => {
  await Promise.all([store.fetchSettings(), store.fetchAvailableTeams()])
  moodEnabled.value = store.settings.moodEnabled
  defaultPostTeamId.value = store.settings.defaultPostTeamId
  defaultCategory.value = store.settings.defaultCategory ?? 'OTHER'
  reminderEnabled.value = store.settings.reminderEnabled
  reminderTime.value = store.settings.reminderTime ?? ''
})

async function onToggle(value: boolean) {
  moodEnabled.value = value
  await store.updateSettings({ moodEnabled: value })
  moodEnabled.value = store.settings.moodEnabled
}

async function onDefaultTeamChange(teamId: number | null) {
  defaultPostTeamId.value = teamId
  await store.updateSettings({ defaultPostTeamId: teamId })
  defaultPostTeamId.value = store.settings.defaultPostTeamId
}

async function onDefaultCategoryChange(category: ActionMemoCategory) {
  defaultCategory.value = category
  await store.updateSettings({ defaultCategory: category })
  defaultCategory.value = store.settings.defaultCategory ?? 'OTHER'
}

async function onReminderToggle(value: boolean) {
  reminderEnabled.value = value
  if (!value) {
    await store.updateSettings({ reminderEnabled: false })
  } else if (reminderTime.value) {
    await store.updateSettings({ reminderEnabled: true, reminderTime: reminderTime.value })
  } else {
    // 有効にしたが時刻未設定 — トグルだけ保存（時刻設定後に再送信）
    await store.updateSettings({ reminderEnabled: true })
  }
  reminderEnabled.value = store.settings.reminderEnabled
}

async function onReminderTimeChange(event: Event) {
  const value = (event.target as HTMLInputElement).value
  reminderTime.value = value
  if (reminderEnabled.value && value) {
    await store.updateSettings({ reminderEnabled: true, reminderTime: value })
    reminderTime.value = store.settings.reminderTime ?? ''
  }
}

function goBack() {
  router.push('/action-memo')
}
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <header class="flex items-center gap-2">
      <button
        type="button"
        class="rounded-lg px-2 py-1 text-sm text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-700"
        data-testid="action-memo-settings-back"
        @click="goBack"
      >
        <i class="pi pi-arrow-left mr-1 text-xs" />
        {{ t('action_memo.page.back_to_memo') }}
      </button>
    </header>

    <h1 class="text-xl font-bold">{{ t('action_memo.settings.title') }}</h1>

    <div
      v-if="store.error"
      class="rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-200"
      role="alert"
    >
      {{ t(store.error) }}
    </div>

    <!-- 気分入力 -->
    <section
      class="flex flex-col gap-3 rounded-2xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
    >
      <div class="flex items-start justify-between gap-3">
        <div class="flex-1">
          <p class="text-sm font-semibold text-surface-800 dark:text-surface-100">
            {{ t('action_memo.settings.mood_enabled.label') }}
          </p>
          <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
            {{ t('action_memo.settings.mood_enabled.description') }}
          </p>
        </div>
        <label class="inline-flex cursor-pointer items-center" data-testid="mood-enabled-toggle">
          <input
            type="checkbox"
            class="peer sr-only"
            :checked="moodEnabled"
            data-testid="mood-enabled-checkbox"
            @change="onToggle(($event.target as HTMLInputElement).checked)"
          >
          <span
            class="relative h-6 w-11 rounded-full bg-surface-300 transition-colors after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:bg-white after:transition-transform after:content-[''] peer-checked:bg-primary peer-checked:after:translate-x-5 dark:bg-surface-600"
          />
        </label>
      </div>
    </section>

    <!-- Phase 3: デフォルトカテゴリ -->
    <section
      class="flex flex-col gap-3 rounded-2xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
    >
      <p class="text-sm font-semibold text-surface-800 dark:text-surface-100">
        {{ t('action_memo.phase3.settings.default_category') }}
      </p>
      <CategorySelector
        :model-value="defaultCategory"
        data-testid="settings-default-category"
        @update:model-value="onDefaultCategoryChange"
      />
    </section>

    <!-- Phase 3: デフォルト投稿先チーム -->
    <section
      class="flex flex-col gap-3 rounded-2xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      data-testid="settings-default-team-section"
    >
      <DefaultTeamPicker
        :available-teams="store.availableTeams"
        :model-value="defaultPostTeamId"
        @update:model-value="onDefaultTeamChange"
      />
    </section>

    <!-- Phase 4-β: リマインド設定 -->
    <section
      class="flex flex-col gap-3 rounded-2xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      data-testid="settings-reminder-section"
    >
      <div class="flex items-start justify-between gap-3">
        <div class="flex-1">
          <p class="text-sm font-semibold text-surface-800 dark:text-surface-100">
            {{ t('action_memo.settings.reminder.label') }}
          </p>
          <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
            {{ t('action_memo.settings.reminder.description') }}
          </p>
        </div>
        <label class="inline-flex cursor-pointer items-center" data-testid="reminder-enabled-toggle">
          <input
            type="checkbox"
            class="peer sr-only"
            :checked="reminderEnabled"
            data-testid="reminder-enabled-checkbox"
            @change="onReminderToggle(($event.target as HTMLInputElement).checked)"
          >
          <span
            class="relative h-6 w-11 rounded-full bg-surface-300 transition-colors after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:bg-white after:transition-transform after:content-[''] peer-checked:bg-primary peer-checked:after:translate-x-5 dark:bg-surface-600"
          />
        </label>
      </div>
      <div v-if="reminderEnabled" class="flex items-center gap-3 pt-1">
        <label class="text-sm text-surface-700 dark:text-surface-300" for="reminder-time">
          {{ t('action_memo.settings.reminder.time_label') }}
        </label>
        <input
          id="reminder-time"
          type="time"
          :value="reminderTime"
          class="rounded-lg border border-surface-300 bg-surface-0 px-3 py-1.5 text-sm dark:border-surface-600 dark:bg-surface-900"
          data-testid="reminder-time-input"
          @change="onReminderTimeChange($event)"
        >
      </div>
    </section>
  </div>
</template>
