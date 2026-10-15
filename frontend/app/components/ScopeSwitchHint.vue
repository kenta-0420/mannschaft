<script setup lang="ts">
/**
 * 「チームまたは組織スコープに切り替えてください」と案内する画面に添える、
 * “どこで切り替えるのか” の導線（CMP-260907-0850）。
 *
 * 現在スコープの正本はルート（URL）であり、チーム／組織のページを開くとそのスコープになる。
 * 案内文だけあって切替手段が示されていないと利用者が詰まるため、
 * ハブ（/teams・/organizations）への導線をその場に置く。
 */
const { t } = useI18n()
</script>

<template>
  <div class="flex flex-col gap-2" data-testid="scope-switch-hint">
    <p class="text-sm text-surface-600 dark:text-surface-300">
      {{ t('scopeSwitch.hint') }}
    </p>
    <div class="flex flex-wrap items-center gap-2">
      <NuxtLink
        to="/teams"
        class="flex items-center gap-1.5 rounded-md border border-surface-300 px-3 py-1.5 text-sm text-surface-700 hover:bg-surface-100 dark:border-surface-600 dark:text-surface-200 dark:hover:bg-surface-800"
      >
        <i class="pi pi-users text-xs" aria-hidden="true" />
        {{ t('scopeSwitch.goTeams') }}
      </NuxtLink>
      <NuxtLink
        to="/organizations"
        class="flex items-center gap-1.5 rounded-md border border-surface-300 px-3 py-1.5 text-sm text-surface-700 hover:bg-surface-100 dark:border-surface-600 dark:text-surface-200 dark:hover:bg-surface-800"
      >
        <i class="pi pi-building text-xs" aria-hidden="true" />
        {{ t('scopeSwitch.goOrganizations') }}
      </NuxtLink>
    </div>
  </div>
</template>
