<script setup lang="ts">
import type { PublicOrganizationSearchResult } from '~/types/public'

/**
 * F19.1 Phase 4 公開組織検索結果カード。
 *
 * 組織名 / アイコン / メンバー数 / 最終投稿日 / 詳細リンク を表示。
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1
 */
defineProps<{
  organization: PublicOrganizationSearchResult
}>()

const { t } = useI18n()

function formatDate(isoDate: string | null): string {
  if (!isoDate) return t('public.discover.card.noPost')
  return t('public.discover.card.lastPost', { date: isoDate.slice(0, 10) })
}
</script>

<template>
  <Card class="h-full" data-testid="discover-organization-card">
    <template #content>
      <div class="flex items-start gap-3">
        <!-- アイコン -->
        <div class="shrink-0">
          <img
            v-if="organization.iconUrl"
            :src="organization.iconUrl"
            :alt="organization.name"
            class="h-14 w-14 rounded-lg object-cover"
          >
          <div
            v-else
            class="flex h-14 w-14 items-center justify-center rounded-lg bg-surface-200 dark:bg-surface-700"
            aria-hidden="true"
          >
            <i class="pi pi-building text-xl text-surface-500" />
          </div>
        </div>

        <!-- テキスト -->
        <div class="min-w-0 flex-1">
          <h3 class="truncate text-base font-bold text-surface-900 dark:text-surface-50">
            {{ organization.name }}
          </h3>
          <!-- F01.2.1: チーム加盟を受け付けている組織のバッジ -->
          <span
            v-if="organization.acceptingTeamApplications"
            class="mt-1 inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-900/30 dark:text-green-400"
            data-testid="accepting-badge"
          >
            <i class="pi pi-sitemap text-[0.7rem]" aria-hidden="true" />
            {{ t('teamAffiliation.badge_accepting') }}
          </span>
          <p class="mt-1 text-sm text-surface-500">
            {{ t('public.discover.card.memberCount', { count: organization.memberCount }) }}
          </p>
          <p class="text-sm text-surface-400">
            {{ formatDate(organization.lastPostDate) }}
          </p>
        </div>
      </div>

      <div class="mt-4">
        <NuxtLink :to="`/public/organizations/${organization.slug}`">
          <Button
            :label="t('public.discover.card.viewDetail')"
            severity="secondary"
            outlined
            size="small"
            class="w-full"
          />
        </NuxtLink>
      </div>
    </template>
  </Card>
</template>
