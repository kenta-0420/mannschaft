<script setup lang="ts">
import type { ServiceRecordResponse } from '~/types/service'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const { getRecords, createRecord } = useServiceRecordApi()
const notification = useNotification()
const authStore = useAuthStore()

const records = ref<ServiceRecordResponse[]>([])
const loading = ref(false)

const showCreateDialog = ref(false)
const saving = ref(false)
const form = ref({
  targetUserId: authStore.currentUser?.id ?? 0,
  serviceDate: new Date().toISOString().slice(0, 10),
  title: '',
  body: '',
})

async function load() {
  loading.value = true
  try {
    const res = await getRecords(teamId)
    records.value = res.data
  } catch {
    notification.error('サービス履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  form.value = {
    targetUserId: authStore.currentUser?.id ?? 0,
    serviceDate: new Date().toISOString().slice(0, 10),
    title: '',
    body: '',
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.title.trim()) return
  saving.value = true
  try {
    await createRecord(teamId, {
      targetUserId: form.value.targetUserId,
      serviceDate: form.value.serviceDate,
      title: form.value.title,
      body: form.value.body || undefined,
    })
    notification.success('サービス履歴を登録しました')
    showCreateDialog.value = false
    await load()
  } catch {
    notification.error('サービス履歴の登録に失敗しました')
  } finally {
    saving.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="サービス履歴" />

    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="flex flex-col gap-2">
      <SectionCard
        v-for="rec in records"
        :key="rec.id"
        class="flex items-center gap-4"
      >
        <Avatar :label="rec.targetUser?.displayName?.charAt(0) || '?'" shape="circle" />
        <div class="min-w-0 flex-1">
          <h3 class="text-sm font-semibold">{{ rec.title }}</h3>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <span>{{ rec.targetUser?.displayName }}</span>
            <span>{{ rec.serviceDate }}</span>
            <span
              class="rounded px-1.5 py-0.5"
              :class="
                rec.status === 'CONFIRMED'
                  ? 'bg-green-100 text-green-700'
                  : 'bg-surface-100 text-surface-600'
              "
              >{{ rec.status === 'CONFIRMED' ? '確定' : '下書き' }}</span
            >
          </div>
        </div>
      </SectionCard>
      <DashboardEmptyState
        v-if="records.length === 0"
        icon="pi pi-list"
        message="サービス履歴がありません"
      />
    </div>

    <Dialog v-model:visible="showCreateDialog" modal header="サービス履歴を追加" :style="{ width: '28rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
          <InputText v-model="form.title" placeholder="例: カット・カラー" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">施術日 <span class="text-red-500">*</span></label>
          <InputText v-model="form.serviceDate" type="date" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">詳細メモ</label>
          <Textarea v-model="form.body" rows="3" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" :disabled="saving" />
        <Button label="登録" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
