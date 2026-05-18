<script setup lang="ts">
// F09.17 推定リーチ プレビュー表示
// 設計書 §4 POST /preview のレスポンスを描画
import type { AdCampaignPreviewResponse, AdChannelType } from '~/types/adMessagingCampaign'

const { t } = useI18n()

defineProps<{
  preview: AdCampaignPreviewResponse | null
  loading?: boolean
}>()

const channelOrder: AdChannelType[] = ['ANNOUNCEMENT', 'EMAIL', 'PUSH', 'BANNER']
</script>

<template>
  <SectionCard :title="t('advertising.advertiser_crud.preview.title')">
    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner style="width:32px;height:32px" />
      <span class="ml-3 text-sm text-surface-500">{{ t('advertising.advertiser_crud.preview.loading') }}</span>
    </div>
    <div v-else-if="!preview" class="py-6 text-center text-sm text-surface-500">
      {{ t('advertising.advertiser_crud.preview.no_data') }}
    </div>
    <div v-else class="space-y-4">
      <div>
        <p class="text-sm text-surface-500">{{ t('advertising.advertiser_crud.preview.estimated_range') }}</p>
        <p class="text-2xl font-bold">{{ preview.label || preview.range }}</p>
        <Message
          v-if="preview.range === 'UNDER_100'"
          severity="warn"
          :closable="false"
          class="mt-2"
        >
          {{ t('advertising.advertiser_crud.preview.under_100_warning') }}
        </Message>
      </div>

      <div v-if="preview.channelCounts">
        <p class="mb-2 text-sm font-medium">{{ t('advertising.advertiser_crud.preview.channel_breakdown') }}</p>
        <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div
            v-for="ch in channelOrder"
            :key="ch"
            class="rounded-lg border border-surface-300 p-3 text-center dark:border-surface-600"
          >
            <p class="text-xs text-surface-500">{{ t(`advertising.channel.${ch.toLowerCase()}`) }}</p>
            <p class="text-lg font-bold">
              {{ (preview.channelCounts?.[ch] ?? 0).toLocaleString() }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </SectionCard>
</template>
