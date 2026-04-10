<script setup lang="ts">
import type { UpdateProfileRequest } from '~/types/user-settings'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { changeLocale } = useLocale()
const { getProfile, updateProfile } = useUserSettingsApi()

const loading = ref(true)
const saving = ref(false)

const form = ref({ locale: 'ja', timezone: 'Asia/Tokyo' })
const fullProfile = ref<UpdateProfileRequest>({})

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

onMounted(async () => {
  try {
    const res = await getProfile()
    const d = res.data
    fullProfile.value = {
      lastName: d.lastName,
      firstName: d.firstName,
      lastNameKana: d.lastNameKana,
      firstNameKana: d.firstNameKana,
      displayName: d.displayName,
      nickname2: d.nickname2,
      isSearchable: d.isSearchable,
      avatarUrl: d.avatarUrl ?? undefined,
      phoneNumber: d.phoneNumber,
      locale: d.locale,
      timezone: d.timezone,
    }
    form.value.locale = res.data.locale || 'ja'
    form.value.timezone = res.data.timezone || 'Asia/Tokyo'
  } catch {
    notification.error('設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
})

async function save() {
  saving.value = true
  try {
    await updateProfile({
      ...fullProfile.value,
      locale: form.value.locale,
      timezone: form.value.timezone,
    })
    await changeLocale(form.value.locale)
    notification.success('言語・タイムゾーンを更新しました')
  } catch {
    notification.error('更新に失敗しました')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">言語・タイムゾーン</h1>
    </div>

    <PageLoading v-if="loading" />

    <div
      v-else
      class="fade-in rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">表示言語</label>
          <Select
            v-model="form.locale"
            :options="localeOptions"
            option-label="name"
            option-value="code"
            class="w-full"
            translate="no"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">タイムゾーン</label>
          <Select
            v-model="form.timezone"
            :options="timezoneOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            translate="no"
          />
        </div>

        <div class="flex justify-end">
          <Button label="保存" icon="pi pi-check" :loading="saving" @click="save" />
        </div>
      </div>
    </div>
  </div>
</template>
