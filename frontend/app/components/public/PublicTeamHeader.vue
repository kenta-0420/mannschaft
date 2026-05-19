<script setup lang="ts">
import type { PublicTeamResponse } from '~/types/public'

/**
 * F19.1 公開チームヘッダー。
 *
 * チーム名 / メンバー数 / 設立年 / 公式サイト / 理念 / 地図 の表示。
 * 個人特定情報（個別メンバー名等）は含めない。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.1 / §4.5 / §8.2
 */
const props = defineProps<{
  team: PublicTeamResponse
}>()

const { t } = useI18n()

const subtitle = computed(() => {
  const parts: string[] = []
  if (props.team.prefecture) parts.push(props.team.prefecture)
  if (props.team.city) parts.push(props.team.city)
  return parts.join(' / ')
})

const formattedMemberCount = computed(() => {
  const n = props.team.memberCount ?? 0
  return t('public.team.memberCount', { n })
})
</script>

<template>
  <section class="space-y-6">
    <!-- バナー -->
    <div v-if="team.bannerUrl" class="overflow-hidden rounded-lg">
      <img
        :src="team.bannerUrl"
        :alt="team.name"
        class="aspect-[16/6] w-full object-cover"
        loading="eager"
      >
    </div>

    <!-- ヘッダー本体 -->
    <header class="flex items-start gap-4">
      <img
        v-if="team.iconUrl"
        :src="team.iconUrl"
        :alt="team.name"
        class="h-20 w-20 shrink-0 rounded-lg object-cover"
      >
      <div class="flex-1">
        <h1 class="text-2xl font-bold text-surface-900 dark:text-surface-50 sm:text-3xl">
          {{ team.name }}
        </h1>
        <p v-if="subtitle" class="mt-1 text-sm text-surface-500">
          {{ subtitle }}
        </p>
        <div class="mt-3 flex flex-wrap gap-3 text-sm text-surface-600 dark:text-surface-300">
          <span>
            <strong class="font-medium text-surface-700 dark:text-surface-200">
              {{ t('public.team.memberCountLabel') }}:
            </strong>
            {{ formattedMemberCount }}
          </span>
          <span v-if="team.establishedDate">
            {{ t('public.team.establishedAt', { date: team.establishedDate }) }}
          </span>
          <a
            v-if="team.homepageUrl"
            :href="team.homepageUrl"
            target="_blank"
            rel="noopener noreferrer nofollow"
            class="text-primary underline hover:no-underline"
          >
            {{ t('public.team.homepageLabel') }}
          </a>
        </div>
      </div>
    </header>

    <!-- 理念 -->
    <section v-if="team.philosophy" aria-labelledby="philosophy-heading">
      <h2 id="philosophy-heading" class="mb-2 text-lg font-bold">
        {{ t('public.team.philosophyTitle') }}
      </h2>
      <p class="whitespace-pre-line text-sm text-surface-700 dark:text-surface-200">
        {{ team.philosophy }}
      </p>
    </section>

    <!-- 地図 -->
    <section v-if="team.mapEmbedUrl" aria-labelledby="map-heading">
      <h2 id="map-heading" class="mb-2 text-lg font-bold">
        {{ t('public.team.mapTitle') }}
      </h2>
      <PublicMapEmbed :url="team.mapEmbedUrl" :title="t('public.team.mapTitle')" />
    </section>
  </section>
</template>
