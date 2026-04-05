<script setup lang="ts">
import type { Timetable, WeeklyView } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const timetableApi = useTimetableApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const timetables = ref<Timetable[]>([])
const selectedTimetable = ref<Timetable | null>(null)
const weeklyView = ref<WeeklyView | null>(null)
const loading = ref(true)
const activeTab = ref('0')

const dayLabels = ['月', '火', '水', '木', '金', '土', '日']
const statusLabel = (s: string) =>
  ({ DRAFT: '下書き', ACTIVE: '運用中', ARCHIVED: 'アーカイブ' })[s] ?? s
const statusSeverity = (s: string) =>
  ({ DRAFT: 'warn', ACTIVE: 'success', ARCHIVED: 'secondary' })[s] ?? 'info'

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    timetables.value = await timetableApi.list(teamId.value)
    const active = timetables.value.find((t) => t.status === 'ACTIVE')
    if (active) await selectTimetable(active)
  } catch {
    notification.error('時間割の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function selectTimetable(tt: Timetable) {
  selectedTimetable.value = tt
  try {
    weeklyView.value = await timetableApi.getWeekly(teamId.value, tt.id)
  } catch {
    notification.error('週間ビューの取得に失敗しました')
  }
}

async function handleActivate(id: number) {
  try {
    await timetableApi.activate(teamId.value, id)
    notification.success('時間割を有効化しました')
    await loadData()
  } catch {
    notification.error('有効化に失敗しました')
  }
}

async function handleExportPdf() {
  if (!selectedTimetable.value) return
  try {
    const res = await timetableApi.exportPdf(teamId.value, selectedTimetable.value.id)
    window.open(res.url, '_blank')
  } catch {
    notification.error('PDF出力に失敗しました')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">時間割管理</h1>
      <div class="flex gap-2">
        <Button
          v-if="selectedTimetable"
          label="PDF出力"
          icon="pi pi-file-pdf"
          severity="secondary"
          size="small"
          @click="handleExportPdf"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">週間ビュー</Tab>
          <Tab value="1">時間割一覧</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <div v-if="weeklyView" class="overflow-x-auto">
              <table class="w-full border-collapse text-sm">
                <thead>
                  <tr>
                    <th
                      class="border border-surface-300 bg-surface-50 p-2 dark:border-surface-600 dark:bg-surface-800"
                    >
                      時限
                    </th>
                    <th
                      v-for="day in weeklyView.days"
                      :key="day.dayOfWeek"
                      class="border border-surface-300 bg-surface-50 p-2 dark:border-surface-600 dark:bg-surface-800"
                    >
                      {{ dayLabels[day.dayOfWeek - 1] }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="period in weeklyView.periods" :key="period.periodNumber">
                    <td
                      class="border border-surface-300 bg-surface-50 p-2 text-center dark:border-surface-600 dark:bg-surface-800"
                    >
                      <div class="font-medium">{{ period.label }}</div>
                      <div class="text-xs text-surface-400">
                        {{ period.startTime }}-{{ period.endTime }}
                      </div>
                    </td>
                    <td
                      v-for="day in weeklyView.days"
                      :key="day.dayOfWeek"
                      class="border border-surface-300 p-2 dark:border-surface-600"
                    >
                      <template
                        v-for="slot in day.slots.filter(
                          (s) => s.periodNumber === period.periodNumber,
                        )"
                        :key="slot.id"
                      >
                        <div
                          class="rounded p-1"
                          :style="slot.color ? { backgroundColor: slot.color + '20' } : {}"
                        >
                          <p
                            class="font-medium"
                            :class="
                              slot.change?.changeType === 'CANCEL'
                                ? 'line-through text-surface-400'
                                : ''
                            "
                          >
                            {{
                              slot.change?.changeType === 'CANCEL'
                                ? '休講'
                                : (slot.change?.newSubject ?? slot.subject)
                            }}
                          </p>
                          <p
                            v-if="slot.teacher || slot.change?.newTeacher"
                            class="text-xs text-surface-500"
                          >
                            {{ slot.change?.newTeacher ?? slot.teacher }}
                          </p>
                          <p
                            v-if="slot.room || slot.change?.newRoom"
                            class="text-xs text-surface-400"
                          >
                            {{ slot.change?.newRoom ?? slot.room }}
                          </p>
                        </div>
                      </template>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="py-12 text-center text-surface-500">
              <i class="pi pi-table mb-2 text-4xl" />
              <p>有効な時間割がありません</p>
            </div>
          </TabPanel>
          <TabPanel value="1">
            <DataTable
              :value="timetables"
              data-key="id"
              striped-rows
              @row-click="(e: { data: Timetable }) => selectTimetable(e.data)"
            >
              <template #empty
                ><div class="py-8 text-center text-surface-500">時間割がありません</div></template
              >
              <Column field="name" header="時間割名" />
              <Column field="termName" header="学期" />
              <Column header="ステータス">
                <template #body="{ data }"
                  ><Badge :value="statusLabel(data.status)" :severity="statusSeverity(data.status)"
                /></template>
              </Column>
              <Column v-if="isAdmin" header="操作" style="width: 150px">
                <template #body="{ data }">
                  <Button
                    v-if="data.status === 'DRAFT'"
                    icon="pi pi-check"
                    size="small"
                    text
                    severity="success"
                    @click.stop="handleActivate(data.id)"
                  />
                </template>
              </Column>
            </DataTable>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
