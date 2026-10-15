<script setup lang="ts">
/**
 * 広告の受信設定 使い方ガイド コンテンツ
 *
 * /settings/ad-preferences ページの各機能を4枚のカードで説明する。
 * AdPreferencesGuideModal から呼び出される。
 */

const { t, tm } = useI18n()
type StepRecord = Record<string, string>

function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const channelsSteps = computed<string[]>(() =>
  resolveSteps('advertising.settings_ad_preferences_guide.channels.steps'),
)
const blocklistSteps = computed<string[]>(() =>
  resolveSteps('advertising.settings_ad_preferences_guide.blocklist.steps'),
)
const rotateTokensSteps = computed<string[]>(() =>
  resolveSteps('advertising.settings_ad_preferences_guide.rotate_tokens.steps'),
)
const saveSteps = computed<string[]>(() =>
  resolveSteps('advertising.settings_ad_preferences_guide.save.steps'),
)
</script>

<template>
  <div class="space-y-4">
    <!-- カード1: チャネル別の受信トグル -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-toggle-on text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('advertising.settings_ad_preferences_guide.channels.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('advertising.settings_ad_preferences_guide.channels.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in channelsSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: ブロック済み広告主リスト -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-ban text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('advertising.settings_ad_preferences_guide.blocklist.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('advertising.settings_ad_preferences_guide.blocklist.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in blocklistSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 配信停止リンクの無効化（セキュリティ） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('advertising.settings_ad_preferences_guide.rotate_tokens.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('advertising.settings_ad_preferences_guide.rotate_tokens.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in rotateTokensSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 設定の保存 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-save text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('advertising.settings_ad_preferences_guide.save.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('advertising.settings_ad_preferences_guide.save.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in saveSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
