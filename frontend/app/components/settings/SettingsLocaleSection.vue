<script setup lang="ts">
const profile = defineModel<{ locale: string; timezone: string }>('profile', { required: true })

defineProps<{
  savingLocale: boolean
}>()

defineEmits<{
  save: []
}>()

const localeOptions = [
  { code: 'ja', name: '日本語' },
  { code: 'en', name: 'English' },
  { code: 'zh', name: '中文（简体）' },
  { code: 'ko', name: '한국어' },
  { code: 'es', name: 'Español' },
  { code: 'de', name: 'Deutsch' },
]

const timezoneOptions = [
  { label: 'Asia/Tokyo (JST)', value: 'Asia/Tokyo' },
  { label: 'Asia/Shanghai (CST)', value: 'Asia/Shanghai' },
  { label: 'Asia/Seoul (KST)', value: 'Asia/Seoul' },
  { label: 'Asia/Singapore (SGT)', value: 'Asia/Singapore' },
  { label: 'Asia/Bangkok (ICT)', value: 'Asia/Bangkok' },
  { label: 'Europe/London (GMT/BST)', value: 'Europe/London' },
  { label: 'Europe/Paris (CET/CEST)', value: 'Europe/Paris' },
  { label: 'Europe/Berlin (CET/CEST)', value: 'Europe/Berlin' },
  { label: 'America/New_York (EST/EDT)', value: 'America/New_York' },
  { label: 'America/Chicago (CST/CDT)', value: 'America/Chicago' },
  { label: 'America/Los_Angeles (PST/PDT)', value: 'America/Los_Angeles' },
  { label: 'America/Sao_Paulo (BRT)', value: 'America/Sao_Paulo' },
  { label: 'Australia/Sydney (AEST/AEDT)', value: 'Australia/Sydney' },
  { label: 'Pacific/Auckland (NZST/NZDT)', value: 'Pacific/Auckland' },
  { label: 'UTC', value: 'UTC' },
]
</script>

<template>
  <SectionCard :title="$t('settings.locale.section_title')">
    <div class="space-y-4">
      <div>
        <label translate="no" class="mb-1 block text-sm font-medium">{{ $t('settings.locale.display_language') }}</label>
        <Select
          v-model="profile.locale"
          :options="localeOptions"
          option-label="name"
          option-value="code"
          class="w-full"
          translate="no"
        />
      </div>
      <div>
        <label translate="no" class="mb-1 block text-sm font-medium">{{ $t('settings.locale.timezone') }}</label>
        <Select
          v-model="profile.timezone"
          :options="timezoneOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          translate="no"
        />
      </div>
      <div class="flex justify-end">
        <Button translate="no" :label="$t('button.save')" icon="pi pi-check" :loading="savingLocale" @click="$emit('save')" />
      </div>
    </div>
  </SectionCard>
</template>
