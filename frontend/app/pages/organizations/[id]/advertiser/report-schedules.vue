<script setup lang="ts">
import type { ReportScheduleResponse, ReportFrequency } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
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

const frequencyOptions = [
  { label: '週次（毎週月曜）', value: 'WEEKLY' },
  { label: '月次（毎月1日）', value: 'MONTHLY' },
]

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getReportSchedules(orgId)
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
    await advertiserApi.createReportSchedule(orgId, { frequency: form.value.frequency, recipients })
    success('レポートスケジュールを作成しました')
    showCreate.value = false
    form.value = { frequency: 'WEEKLY', recipients: '' }
    await load()
  }
  catch { showError('作成に失敗しました') }
  finally { creating.value = false }
}

async function remove(id: number) {
  try {
    await advertiserApi.deleteReportSchedule(id, orgId)
    success('削除しました')
    await load()
  }
  catch { showError('削除に失敗しました') }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">定期レポート</h1>
      <Button label="新規作成" icon="pi pi-plus" :disabled="schedules.length >= 3" @click="showCreate = true" />
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-10" />

    <div v-else-if="schedules.length === 0" class="py-10 text-center text-surface-500">
      定期レポートはまだ設定されていません。
    </div>

    <div v-else class="space-y-3">
      <div v-for="s in schedules" :key="s.id" class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
        <div>
          <Tag :value="s.frequency" :severity="s.frequency === 'WEEKLY' ? 'info' : 'warn'" class="mr-2" />
          <span class="text-sm">{{ s.recipients.join(', ') }}</span>
          <p v-if="s.lastSentAt" class="mt-1 text-xs text-surface-400">最終配信: {{ s.lastSentAt }}</p>
        </div>
        <Button icon="pi pi-trash" severity="danger" text @click="remove(s.id)" />
      </div>
    </div>

    <Dialog v-model:visible="showCreate" header="レポートスケジュール作成" :style="{ width: '500px' }" modal>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">配信頻度</label>
        <Select v-model="form.frequency" :options="frequencyOptions" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">配信先メール（カンマ区切り）</label>
        <InputText v-model="form.recipients" class="w-full" placeholder="ads@example.com, manager@example.com" />
      </div>
      <div class="flex justify-end">
        <Button label="作成" icon="pi pi-check" :loading="creating" @click="create" />
      </div>
    </Dialog>
  </div>
</template>
