<script setup lang="ts">
// F09.17 チャネル別本文入力（locale 切替タブ）
// 設計書 §4 POST /channels のバリデーション準拠

import type {
  AdChannelType,
  AdMessagingCampaignChannelRequest,
} from '~/types/adMessagingCampaign'

const { t } = useI18n()

const props = defineProps<{
  modelValue: AdMessagingCampaignChannelRequest
  hideChannelType?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: AdMessagingCampaignChannelRequest): void
}>()

const channelTypes: AdChannelType[] = ['ANNOUNCEMENT', 'EMAIL', 'PUSH', 'BANNER']
const locales = ['ja', 'en', 'zh-CN', 'zh-TW', 'ko', 'pt-BR']

function updateField<K extends keyof AdMessagingCampaignChannelRequest>(
  key: K,
  value: AdMessagingCampaignChannelRequest[K],
) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<template>
  <div class="space-y-3">
    <div v-if="!hideChannelType" class="grid grid-cols-2 gap-3">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.select_channel') }} <span class="text-red-500">*</span></label>
        <Select
          :model-value="modelValue.channelType"
          :options="channelTypes"
          class="w-full"
          @update:model-value="(v) => updateField('channelType', v as AdChannelType)"
        >
          <template #value="{ value }">
            {{ value ? t(`advertising.channel.${(value as string).toLowerCase()}`) : '' }}
          </template>
          <template #option="{ option }">
            {{ t(`advertising.channel.${(option as string).toLowerCase()}`) }}
          </template>
        </Select>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.select_locale') }} <span class="text-red-500">*</span></label>
        <Select
          :model-value="modelValue.locale"
          :options="locales"
          class="w-full"
          @update:model-value="(v) => updateField('locale', String(v ?? 'ja'))"
        />
      </div>
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.subject') }}</label>
      <InputText
        :model-value="modelValue.subject ?? ''"
        class="w-full"
        maxlength="200"
        @update:model-value="(v) => updateField('subject', String(v ?? '') || null)"
      />
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.body_markdown') }} <span class="text-red-500">*</span></label>
      <Textarea
        :model-value="modelValue.bodyMarkdown"
        :rows="6"
        class="w-full"
        maxlength="8000"
        auto-resize
        @update:model-value="(v) => updateField('bodyMarkdown', String(v ?? ''))"
      />
    </div>

    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.cta_label') }}</label>
        <InputText
          :model-value="modelValue.ctaLabel ?? ''"
          class="w-full"
          maxlength="50"
          @update:model-value="(v) => updateField('ctaLabel', String(v ?? '') || null)"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.cta_url') }}</label>
        <InputText
          :model-value="modelValue.ctaUrl ?? ''"
          class="w-full"
          maxlength="500"
          placeholder="https://..."
          @update:model-value="(v) => updateField('ctaUrl', String(v ?? '') || null)"
        />
      </div>
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.image_url') }}</label>
      <InputText
        :model-value="modelValue.imageUrl ?? ''"
        class="w-full"
        maxlength="500"
        @update:model-value="(v) => updateField('imageUrl', String(v ?? '') || null)"
      />
    </div>

    <div v-if="modelValue.channelType === 'BANNER'">
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.channel_form.banner_creative_id') }} <span class="text-red-500">*</span></label>
      <InputNumber
        :model-value="modelValue.bannerCreativeId ?? undefined"
        :min="1"
        class="w-full"
        @update:model-value="(v) => updateField('bannerCreativeId', v == null ? null : Number(v))"
      />
    </div>
  </div>
</template>
