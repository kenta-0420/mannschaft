<script setup lang="ts">
/**
 * F02.5 行動メモ設定画面。
 *
 * <p>Phase 1 では {@code mood_enabled} トグルのみ。
 * 将来的に終業時刻リマインド・週次まとめ曜日カスタマイズ等が追加される想定（設計書 §3）。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

const moodEnabled = ref<boolean>(false)

onMounted(async () => {
  await store.fetchSettings()
  moodEnabled.value = store.settings.moodEnabled
})

async function onToggle(value: boolean) {
  moodEnabled.value = value
  await store.updateSettings({ moodEnabled: value })
  // store の更新が反映されたら値を同期
  moodEnabled.value = store.settings.moodEnabled
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
  </div>
</template>
