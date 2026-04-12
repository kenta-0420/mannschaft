<script setup lang="ts">
import type {
  PresenceStatusResponse,
  PresenceEventResponse,
  PresenceStatsResponse,
} from '~/types/presence'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { loadPermissions } = useRoleAccess('team', teamId)
const presenceApi = usePresenceApi()
const { showError } = useNotification()

const statuses = ref<PresenceStatusResponse[]>([])
const historyList = ref<PresenceEventResponse[]>([])
const stats = ref<PresenceStatsResponse | null>(null)
const loading = ref(true)
const activeTab = ref(0)

// 外出フォーム
const showGoingOutDialog = ref(false)
const destination = ref('')
const expectedReturnAt = ref('')
const goingOutMessage = ref('')

// 帰宅フォーム
const homeMessage = ref('')

async function loadStatus() {
  loading.value = true
  try {
    const res = await presenceApi.getStatus(teamId)
    statuses.value = res.data
  } catch {
    showError('在席情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  try {
    const res = await presenceApi.getHistory(teamId)
    historyList.value = res.data
  } catch {
    showError('履歴の取得に失敗しました')
  }
}

async function loadStats() {
  try {
    const res = await presenceApi.getStats(teamId)
    stats.value = res.data
  } catch {
    showError('統計の取得に失敗しました')
  }
}

function openGoingOut() {
  destination.value = ''
  expectedReturnAt.value = ''
  goingOutMessage.value = ''
  showGoingOutDialog.value = true
}

async function submitGoingOut() {
  try {
    const body: Record<string, unknown> = {}
    if (destination.value) body.destination = destination.value
    if (expectedReturnAt.value) body.expectedReturnAt = expectedReturnAt.value
    if (goingOutMessage.value) body.message = goingOutMessage.value
    await presenceApi.goingOut(teamId, body as Record<string, unknown>)
    showGoingOutDialog.value = false
    await loadStatus()
  } catch {
    showError('外出登録に失敗しました')
  }
}

async function submitHome() {
  try {
    await presenceApi.goHome(teamId, homeMessage.value ? { message: homeMessage.value } : undefined)
    homeMessage.value = ''
    await loadStatus()
  } catch {
    showError('帰宅登録に失敗しました')
  }
}

function statusColor(status: string): string {
  switch (status) {
    case 'HOME':
      return 'text-green-500'
    case 'GOING_OUT':
      return 'text-orange-500'
    default:
      return 'text-surface-400'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'HOME':
      return '在宅'
    case 'GOING_OUT':
      return '外出中'
    default:
      return status
  }
}

onMounted(async () => {
  await loadPermissions()
  await loadStatus()
})

watch(activeTab, (tab) => {
  if (tab === 1) loadHistory()
  if (tab === 2) loadStats()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="在席管理" />
      <div class="flex gap-2">
        <Button label="外出する" icon="pi pi-sign-out" severity="warn" @click="openGoingOut" />
        <Button label="帰宅する" icon="pi pi-home" severity="success" @click="submitHome" />
      </div>
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">ステータス</Tab>
        <Tab :value="1">履歴</Tab>
        <Tab :value="2">統計</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <PageLoading v-if="loading" size="40px" />
          <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <SectionCard
              v-for="s in statuses"
              :key="s.user.id"
            >
              <div class="flex items-center gap-3">
                <div
                  class="flex size-10 items-center justify-center rounded-full bg-surface-100 dark:bg-surface-700"
                >
                  <i class="pi pi-user text-surface-500" />
                </div>
                <div>
                  <p class="font-semibold">{{ s.user.displayName }}</p>
                  <p class="text-sm font-medium" :class="statusColor(s.status)">
                    {{ statusLabel(s.status) }}
                  </p>
                </div>
              </div>
              <div v-if="s.destination" class="mt-2 text-sm text-surface-500">
                <i class="pi pi-map-marker mr-1" />{{ s.destination }}
              </div>
              <div v-if="s.expectedReturnAt" class="mt-1 text-xs text-surface-400">
                帰宅予定: {{ new Date(s.expectedReturnAt).toLocaleString('ja-JP') }}
              </div>
            </SectionCard>
            <DashboardEmptyState
              v-if="statuses.length === 0"
              icon="pi pi-users"
              message="メンバーの在席情報がありません"
              class="col-span-full"
            />
          </div>
        </TabPanel>

        <TabPanel :value="1">
          <div class="flex flex-col gap-2">
            <div
              v-for="event in historyList"
              :key="event.id"
              class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
            >
              <div class="flex items-center gap-3">
                <Tag
                  :value="event.eventType === 'GOING_OUT' ? '外出' : '帰宅'"
                  :severity="event.eventType === 'GOING_OUT' ? 'warn' : 'success'"
                />
                <div>
                  <p class="font-medium">{{ event.user.displayName }}</p>
                  <p v-if="event.destination" class="text-sm text-surface-500">
                    {{ event.destination }}
                  </p>
                  <p v-if="event.message" class="text-sm text-surface-400">{{ event.message }}</p>
                </div>
              </div>
              <span class="text-xs text-surface-400">{{
                new Date(event.createdAt).toLocaleString('ja-JP')
              }}</span>
            </div>
            <div v-if="historyList.length === 0" class="py-8 text-center text-surface-400">
              履歴がありません
            </div>
          </div>
        </TabPanel>

        <TabPanel :value="2">
          <div v-if="stats" class="space-y-4">
            <div class="grid gap-4 sm:grid-cols-4">
              <SectionCard>
                <p class="text-xs text-surface-400">合計イベント</p>
                <p class="text-2xl font-bold">{{ stats.totalEvents }}</p>
              </SectionCard>
              <SectionCard>
                <p class="text-xs text-surface-400">外出</p>
                <p class="text-2xl font-bold text-orange-500">{{ stats.totalGoingOutEvents }}</p>
              </SectionCard>
              <SectionCard>
                <p class="text-xs text-surface-400">帰宅</p>
                <p class="text-2xl font-bold text-green-500">{{ stats.totalHomeEvents }}</p>
              </SectionCard>
              <SectionCard>
                <p class="text-xs text-surface-400">超過</p>
                <p class="text-2xl font-bold text-red-500">{{ stats.overdueCount }}</p>
              </SectionCard>
            </div>

            <div v-if="stats.memberStats.length > 0">
              <h3 class="mb-2 font-semibold">メンバー別統計</h3>
              <DataTable :value="stats.memberStats" striped-rows>
                <Column field="userId" header="ユーザーID" />
                <Column field="goingOutCount" header="外出回数" />
                <Column field="homeCount" header="帰宅回数" />
                <Column field="overdueCount" header="超過回数" />
              </DataTable>
            </div>
          </div>
          <div v-else class="py-8 text-center text-surface-400">
            統計データを読み込んでいます...
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- 外出ダイアログ -->
    <Dialog v-model:visible="showGoingOutDialog" header="外出登録" modal class="w-full max-w-md">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">行き先</label>
          <InputText v-model="destination" class="w-full" placeholder="例: スーパー" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">帰宅予定</label>
          <InputText v-model="expectedReturnAt" type="datetime-local" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">メッセージ</label>
          <InputText v-model="goingOutMessage" class="w-full" placeholder="任意のメッセージ" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showGoingOutDialog = false" />
        <Button label="外出する" severity="warn" @click="submitGoingOut" />
      </template>
    </Dialog>
  </div>
</template>
