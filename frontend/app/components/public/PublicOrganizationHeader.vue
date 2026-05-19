<script setup lang="ts">
import type { PublicOrganizationResponse } from '~/types/public'

/**
 * F19.1 公開組織ヘッダー。
 *
 * 組織名 / 所在地 / 設立年 / 公式サイト / 理念 / 地図 の表示。
 * 個別メンバー名は表示しない（Phase 1 では memberCount もチーム単位のみ）。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.1 / §8.2
 */
const props = defineProps<{
  organization: PublicOrganizationResponse
}>()

const { t } = useI18n()

const subtitle = computed(() => {
  const parts: string[] = []
  if (props.organization.prefecture) parts.push(props.organization.prefecture)
  if (props.organization.city) parts.push(props.organization.city)
  return parts.join(' / ')
})
</script>

<template>
  <section class="space-y-6">
    <div v-if="organization.bannerUrl" class="overflow-hidden rounded-lg">
      <img
        :src="organization.bannerUrl"
        :alt="organization.name"
        class="aspect-[16/6] w-full object-cover"
        loading="eager"
      >
    </div>

    <header class="flex items-start gap-4">
      <img
        v-if="organization.iconUrl"
        :src="organization.iconUrl"
        :alt="organization.name"
        class="h-20 w-20 shrink-0 rounded-lg object-cover"
      >
      <div class="flex-1">
        <h1 class="text-2xl font-bold text-surface-900 dark:text-surface-50 sm:text-3xl">
          {{ organization.name }}
        </h1>
        <p v-if="subtitle" class="mt-1 text-sm text-surface-500">
          {{ subtitle }}
        </p>
        <div class="mt-3 flex flex-wrap gap-3 text-sm text-surface-600 dark:text-surface-300">
          <span v-if="organization.establishedDate">
            {{ t('public.organization.establishedAt', { date: organization.establishedDate }) }}
          </span>
          <a
            v-if="organization.homepageUrl"
            :href="organization.homepageUrl"
            target="_blank"
            rel="noopener noreferrer nofollow"
            class="text-primary underline hover:no-underline"
          >
            {{ t('public.organization.homepageLabel') }}
          </a>
        </div>
      </div>
    </header>

    <section v-if="organization.philosophy" aria-labelledby="org-philosophy-heading">
      <h2 id="org-philosophy-heading" class="mb-2 text-lg font-bold">
        {{ t('public.organization.philosophyTitle') }}
      </h2>
      <p class="whitespace-pre-line text-sm text-surface-700 dark:text-surface-200">
        {{ organization.philosophy }}
      </p>
    </section>

    <section v-if="organization.mapEmbedUrl" aria-labelledby="org-map-heading">
      <h2 id="org-map-heading" class="mb-2 text-lg font-bold">
        {{ t('public.organization.mapTitle') }}
      </h2>
      <PublicMapEmbed
        :url="organization.mapEmbedUrl"
        :title="t('public.organization.mapTitle')"
      />
    </section>
  </section>
</template>
