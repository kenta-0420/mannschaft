<script setup lang="ts">
import type {
  CheckinResponse,
  EventDetailResponse,
  RegistrationResponse,
  TimetableItemResponse,
} from '~/types/event'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  eventId: number
  canEdit: boolean
}>()

const eventApi = useEventApi()
const notification = useNotification()

const event = ref<EventDetailResponse | null>(null)
const registrations = ref<RegistrationResponse[]>([])
const checkins = ref<CheckinResponse[]>([])
const timetableItems = ref<TimetableItemResponse[]>([])
const loading = ref(true)
const activeTab = ref(0)

// 編集ダイアログ
const showEditDialog = ref(false)

function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'PUBLISHED':
      return 'success'
    case 'CANCELLED':
      return 'danger'
    case 'CLOSED':
      return 'warn'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'PUBLISHED':
      return '公開中'
    case 'CANCELLED':
      return 'キャンセル'
    case 'CLOSED':
      return '終了'
    default:
      return status
  }
}

function regStatusLabel(status: string) {
  switch (status) {
    case 'PENDING':
      return '保留中'
    case 'APPROVED':
      return '承認済'
    case 'REJECTED':
      return '拒否'
    case 'CANCELLED':
      return 'キャンセル'
    default:
      return status
  }
}

function regStatusSeverity(status: string) {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'info'
  }
}

async function loadEvent() {
  loading.value = true
  try {
    const res = await eventApi.getEvent(props.scopeType, props.scopeId, props.eventId)
    event.value = res.data
  } catch {
    notification.error('イベント情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadRegistrations() {
  try {
    const res = await eventApi.listRegistrations(props.eventId)
    registrations.value = res.data
  } catch {
    registrations.value = []
  }
}

async function loadCheckins() {
  try {
    const res = await eventApi.listCheckins(props.eventId)
    checkins.value = res.data
  } catch {
    checkins.value = []
  }
}

async function loadTimetable() {
  try {
    const res = await eventApi.getTimetable(props.eventId)
    timetableItems.value = res.data
  } catch {
    timetableItems.value = []
  }
}

async function onPublish() {
  if (!confirm('このイベントを公開しますか？')) return
  try {
    await eventApi.publishEvent(props.scopeType, props.scopeId, props.eventId)
    notification.success('イベントを公開しました')
    await loadEvent()
  } catch {
    notification.error('公開に失敗しました')
  }
}

async function onCancel() {
  if (!confirm('このイベントをキャンセルしますか？')) return
  try {
    await eventApi.cancelEvent(props.scopeType, props.scopeId, props.eventId)
    notification.success('イベントをキャンセルしました')
    await loadEvent()
  } catch {
    notification.error('キャンセルに失敗しました')
  }
}

async function onCloseRegistration() {
  try {
    await eventApi.closeRegistration(props.scopeType, props.scopeId, props.eventId)
    notification.success('受付を終了しました')
    await loadEvent()
  } catch {
    notification.error('受付終了に失敗しました')
  }
}

async function onOpenRegistration() {
  try {
    await eventApi.openRegistration(props.scopeType, props.scopeId, props.eventId)
    notification.success('受付を再開しました')
    await loadEvent()
  } catch {
    notification.error('受付再開に失敗しました')
  }
}

async function onApproveRegistration(regId: number) {
  try {
    await eventApi.approveRegistration(props.eventId, regId)
    notification.success('参加を承認しました')
    await loadRegistrations()
  } catch {
    notification.error('承認に失敗しました')
  }
}

async function onRejectRegistration(regId: number) {
  try {
    await eventApi.rejectRegistration(props.eventId, regId)
    notification.success('参加を拒否しました')
    await loadRegistrations()
  } catch {
    notification.error('拒否に失敗しました')
  }
}

function formatDateTime(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleString('ja-JP')
}

function onSaved() {
  loadEvent()
}

onMounted(async () => {
  await loadEvent()
  await Promise.all([loadRegistrations(), loadCheckins(), loadTimetable()])
})
</script>

<template>
  <div>
    <div v-if="loading" class="flex items-center justify-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="event">
      <!-- ヘッダー -->
      <div class="mb-6 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-bold">
            {{ event.subtitle || event.slug || `イベント #${event.id}` }}
          </h1>
          <div class="mt-2 flex items-center gap-3">
            <Tag :value="statusLabel(event.status)" :severity="statusSeverity(event.status)" />
            <span v-if="event.isPublic" class="text-sm text-green-600"
              ><i class="pi pi-eye mr-1" />一般公開</span
            >
            <span v-else class="text-sm text-surface-500"
              ><i class="pi pi-eye-slash mr-1" />非公開</span
            >
          </div>
        </div>
        <div v-if="canEdit" class="flex gap-2">
          <Button label="編集" icon="pi pi-pencil" outlined @click="showEditDialog = true" />
          <Button
            v-if="event.status === 'DRAFT'"
            label="公開"
            icon="pi pi-send"
            severity="success"
            @click="onPublish"
          />
          <Button
            v-if="event.status === 'PUBLISHED'"
            label="受付終了"
            icon="pi pi-lock"
            severity="warn"
            @click="onCloseRegistration"
          />
          <Button
            v-if="event.status === 'CLOSED'"
            label="受付再開"
            icon="pi pi-lock-open"
            severity="info"
            @click="onOpenRegistration"
          />
          <Button
            v-if="event.status !== 'CANCELLED'"
            label="キャンセル"
            icon="pi pi-times"
            severity="danger"
            outlined
            @click="onCancel"
          />
        </div>
      </div>

      <!-- 基本情報 -->
      <Card class="mb-4">
        <template #content>
          <div class="grid grid-cols-2 gap-4 md:grid-cols-3">
            <div>
              <p class="text-sm text-surface-500">会場</p>
              <p class="font-medium">{{ event.venueName || '—' }}</p>
              <p v-if="event.venueAddress" class="text-sm text-surface-500">
                {{ event.venueAddress }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">定員</p>
              <p class="font-medium">
                {{
                  event.maxCapacity
                    ? `${event.registrationCount} / ${event.maxCapacity}`
                    : `${event.registrationCount}名`
                }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">チェックイン数</p>
              <p class="font-medium">{{ event.checkinCount }}名</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付開始</p>
              <p class="font-medium">{{ formatDateTime(event.registrationStartsAt) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付終了</p>
              <p class="font-medium">{{ formatDateTime(event.registrationEndsAt) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">承認制</p>
              <p class="font-medium">{{ event.isApprovalRequired ? 'はい' : 'いいえ' }}</p>
            </div>
          </div>
          <div v-if="event.summary" class="mt-4">
            <p class="text-sm text-surface-500">概要</p>
            <p class="mt-1 whitespace-pre-wrap">{{ event.summary }}</p>
          </div>
        </template>
      </Card>

      <!-- タブ -->
      <TabView v-model:active-index="activeTab">
        <!-- 参加者タブ -->
        <TabPanel header="参加者">
          <DataTable :value="registrations" data-key="id" row-hover>
            <Column header="ID" field="id" style="width: 80px" />
            <Column header="ユーザーID" style="width: 120px">
              <template #body="{ data }">
                {{ data.userId || data.guestName || '—' }}
              </template>
            </Column>
            <Column header="ステータス" style="width: 120px">
              <template #body="{ data }">
                <Tag
                  :value="regStatusLabel(data.status)"
                  :severity="regStatusSeverity(data.status)"
                />
              </template>
            </Column>
            <Column header="数量" field="quantity" style="width: 80px" />
            <Column header="メモ" field="note" style="min-width: 150px">
              <template #body="{ data }">
                {{ data.note || '—' }}
              </template>
            </Column>
            <Column header="登録日" style="width: 160px">
              <template #body="{ data }">
                {{ formatDateTime(data.createdAt) }}
              </template>
            </Column>
            <Column v-if="canEdit" header="操作" style="width: 120px">
              <template #body="{ data }">
                <div v-if="data.status === 'PENDING'" class="flex gap-1">
                  <Button
                    icon="pi pi-check"
                    text
                    rounded
                    size="small"
                    severity="success"
                    @click="onApproveRegistration(data.id)"
                  />
                  <Button
                    icon="pi pi-times"
                    text
                    rounded
                    size="small"
                    severity="danger"
                    @click="onRejectRegistration(data.id)"
                  />
                </div>
              </template>
            </Column>
            <template #empty>
              <DashboardEmptyState icon="pi pi-users" message="参加者はいません" />
            </template>
          </DataTable>
        </TabPanel>

        <!-- チェックインタブ -->
        <TabPanel header="チェックイン">
          <DataTable :value="checkins" data-key="id" row-hover>
            <Column header="ID" field="id" style="width: 80px" />
            <Column header="種別" field="checkinType" style="width: 120px" />
            <Column header="チェックイン日時" style="width: 200px">
              <template #body="{ data }">
                {{ formatDateTime(data.checkedInAt) }}
              </template>
            </Column>
            <Column header="メモ" field="note" style="min-width: 150px">
              <template #body="{ data }">
                {{ data.note || '—' }}
              </template>
            </Column>
            <template #empty>
              <DashboardEmptyState icon="pi pi-sign-in" message="チェックインはありません" />
            </template>
          </DataTable>
        </TabPanel>

        <!-- タイムテーブルタブ -->
        <TabPanel header="タイムテーブル">
          <DataTable :value="timetableItems" data-key="id" row-hover>
            <Column header="タイトル" field="title" style="min-width: 200px" />
            <Column header="登壇者" field="speaker" style="width: 150px">
              <template #body="{ data }">
                {{ data.speaker || '—' }}
              </template>
            </Column>
            <Column header="開始" style="width: 160px">
              <template #body="{ data }">
                {{ formatDateTime(data.startAt) }}
              </template>
            </Column>
            <Column header="終了" style="width: 160px">
              <template #body="{ data }">
                {{ formatDateTime(data.endAt) }}
              </template>
            </Column>
            <Column header="場所" field="location" style="width: 150px">
              <template #body="{ data }">
                {{ data.location || '—' }}
              </template>
            </Column>
            <template #empty>
              <DashboardEmptyState icon="pi pi-clock" message="タイムテーブルはありません" />
            </template>
          </DataTable>
        </TabPanel>
      </TabView>

      <!-- 編集ダイアログ -->
      <EventForm
        v-model:visible="showEditDialog"
        :scope-type="props.scopeType"
        :scope-id="props.scopeId"
        :event-id="props.eventId"
        @saved="onSaved"
      />
    </div>
  </div>
</template>
