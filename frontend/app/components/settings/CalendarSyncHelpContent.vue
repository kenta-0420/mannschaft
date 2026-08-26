<script setup lang="ts">
/**
 * Google Calendar連携（チーム/組織カレンダー同期）使い方ガイドの本体コンテンツ。
 *
 * SecurityHelpContent / ProjectGuideContent と同じ
 * 「色付き丸アイコン＋カード」方式で、連携方法・個別トグル・同期タイミングの3点を案内する。
 * i18n: calendar_sync_help.*
 */
const { t } = useI18n()
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('calendar_sync_help.description') }}
    </p>

    <!-- Google Calendar連携とは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-calendar text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('calendar_sync_help.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('calendar_sync_help.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- 連携する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-link text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('calendar_sync_help.connect.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('calendar_sync_help.connect.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- 同期のON/OFFを個別に設定する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-sliders-h text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('calendar_sync_help.toggles.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('calendar_sync_help.toggles.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('calendar_sync_help.toggles.item_personal') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('calendar_sync_help.toggles.item_team') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ t('calendar_sync_help.toggles.item_org') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- 同期のタイミング -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-refresh text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('calendar_sync_help.sync_behavior.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('calendar_sync_help.sync_behavior.body') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
