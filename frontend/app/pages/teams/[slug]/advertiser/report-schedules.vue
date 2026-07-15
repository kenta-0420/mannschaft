<script setup lang="ts">
// F09.19.6 チームスコープ 定期レポートページ。
// 組織版 (pages/organizations/[slug]/advertiser/report-schedules.vue) を team scope で読み替えたもの。

import type { ReportScheduleResponse, ReportFrequency } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const schedules = ref<ReportScheduleResponse[]>([])
const loading = ref(true)
const showCreate = ref(false)
const creating = ref(false)
const form = ref({
  frequency: 'WEEKLY' as ReportFrequency,
  recipients: '',
})

const frequencyOptions = computed(() => [
  { label: t('advertising.teams_page.report_schedules.frequency_weekly'), value: 'WEEKLY' },
  { label: t('advertising.teams_page.report_schedules.frequency_monthly'), value: 'MONTHLY' },
])

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getReportSchedules('TEAM', teamSlug)
    schedules.value = res.data
  }
  catch { schedules.value = [] }
  finally { loading.value = false }
}

async function create() {
  const recipients = form.value.recipients.split(',').map(s => s.trim()).filter(Boolean)
  if (recipients.length === 0) return
  creating.value = true
  try {
    await advertiserApi.createReportSchedule('TEAM', teamSlug, { frequency: form.value.frequency, recipients })
    success(t('advertising.teams_page.report_schedules.created_toast'))
    showCreate.value = false
    form.value = { frequency: 'WEEKLY', recipients: '' }
    await load()
  }
  catch { showError(t('advertising.teams_page.report_schedules.create_failed_toast')) }
  finally { creating.value = false }
}

async function remove(id: number) {
  try {
    await advertiserApi.deleteReportSchedule('TEAM', teamSlug, id)
    success(t('advertising.teams_page.report_schedules.deleted_toast'))
    await load()
  }
  catch { showError(t('advertising.teams_page.report_schedules.delete_failed_toast')) }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('advertising.teams_page.report_schedules.title')" :back-to="`/teams/${teamSlug}/advertiser`" />
      <Button :label="t('advertising.teams_page.report_schedules.create_button')" icon="pi pi-plus" :disabled="schedules.length >= 3" @click="showCreate = true" />
    </div>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <div v-else-if="schedules.length === 0" class="py-10 text-center text-surface-500">
      {{ t('advertising.teams_page.report_schedules.empty') }}
    </div>

    <div v-else class="space-y-3">
      <div v-for="s in schedules" :key="s.id" class="flex items-center justify-between rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
        <div>
          <Tag :value="s.frequency" :severity="s.frequency === 'WEEKLY' ? 'info' : 'warn'" class="mr-2" />
          <span class="text-sm">{{ s.recipients.join(', ') }}</span>
          <p v-if="s.lastSentAt" class="mt-1 text-xs text-surface-400">{{ t('advertising.teams_page.report_schedules.last_sent_label', { date: s.lastSentAt }) }}</p>
        </div>
        <Button icon="pi pi-trash" severity="danger" text @click="remove(s.id)" />
      </div>
    </div>

    <Dialog v-model:visible="showCreate" :header="t('advertising.teams_page.report_schedules.dialog_title')" :style="{ width: '500px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.report_schedules.form_frequency_label') }}</label>
        <Select v-model="form.frequency" :options="frequencyOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.report_schedules.form_recipients_label') }}</label>
        <InputText v-model="form.recipients" class="w-full" :placeholder="t('advertising.teams_page.report_schedules.form_recipients_placeholder')" />
      </div>
      <div class="flex justify-end">
        <Button :label="t('advertising.teams_page.report_schedules.submit_button')" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
