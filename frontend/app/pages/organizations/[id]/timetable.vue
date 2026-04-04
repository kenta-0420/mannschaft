<script setup lang="ts">
import type { TimetablePeriod, TimetableTerm } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const timetableApi = useTimetableApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

const periods = ref<TimetablePeriod[]>([])
const terms = ref<TimetableTerm[]>([])
const loading = ref(true)
const activeTab = ref('0')
const showTermDialog = ref(false)
const termForm = ref({ name: '', startDate: '', endDate: '' })

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const [p, t] = await Promise.all([
      timetableApi.listPeriods(orgId.value),
      timetableApi.listTerms('organization', orgId.value),
    ])
    periods.value = p
    terms.value = t
  } catch {
    notification.error('時間割設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <h1 class="mb-6 text-2xl font-bold">時間割設定（組織テンプレート）</h1>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">時限定義</Tab>
          <Tab value="1">学期テンプレート</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <p class="mb-4 text-sm text-surface-500">
              組織レベルで時限の時間帯を定義します。各チームの時間割はこの定義を継承します。
            </p>
            <DataTable :value="periods" data-key="id" striped-rows>
              <template #empty
                ><div class="py-8 text-center text-surface-500">
                  時限が定義されていません
                </div></template
              >
              <Column field="periodNumber" header="時限" style="width: 80px" />
              <Column field="label" header="ラベル" />
              <Column field="startTime" header="開始" style="width: 100px" />
              <Column field="endTime" header="終了" style="width: 100px" />
            </DataTable>
          </TabPanel>
          <TabPanel value="1">
            <div class="mb-4 flex items-center justify-between">
              <p class="text-sm text-surface-500">
                組織レベルの学期定義。チームはこれを継承またはオーバーライドできます。
              </p>
              <Button
                v-if="isAdmin"
                label="学期追加"
                icon="pi pi-plus"
                size="small"
                @click="showTermDialog = true"
              />
            </div>
            <DataTable :value="terms" data-key="id" striped-rows>
              <template #empty
                ><div class="py-8 text-center text-surface-500">
                  学期が定義されていません
                </div></template
              >
              <Column field="name" header="学期名" />
              <Column header="開始日"
                ><template #body="{ data }">{{
                  new Date(data.startDate).toLocaleDateString('ja-JP')
                }}</template></Column
              >
              <Column header="終了日"
                ><template #body="{ data }">{{
                  new Date(data.endDate).toLocaleDateString('ja-JP')
                }}</template></Column
              >
            </DataTable>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <Dialog
        v-model:visible="showTermDialog"
        header="学期追加"
        :modal="true"
        class="w-full max-w-md"
      >
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">学期名</label
            ><InputText v-model="termForm.name" class="w-full" placeholder="例: 1学期" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">開始日</label
            ><InputText v-model="termForm.startDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日</label
            ><InputText v-model="termForm.endDate" type="date" class="w-full" />
          </div>
        </div>
        <template #footer>
          <Button label="キャンセル" severity="secondary" @click="showTermDialog = false" />
          <Button
            label="追加"
            icon="pi pi-check"
            :disabled="!termForm.name || !termForm.startDate || !termForm.endDate"
            @click="showTermDialog = false"
          />
        </template>
      </Dialog>
    </template>
  </div>
</template>
