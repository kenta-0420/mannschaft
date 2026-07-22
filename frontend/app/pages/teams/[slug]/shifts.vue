<script setup lang="ts">
import dayjs from 'dayjs'
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const shiftApi = useShiftApi()

// 数値 teamId が必要な API 向けの解決経路。
// `TeamResponse.id` は slug と同値の URL 識別子であり数値 ID ではないため、
// 必ず `numericId` を使う（親 pages/teams/[slug].vue が provide 済みで追加往復なし）。
const { team } = useTeamShellContext()
const teamNumericId = computed<number | null>(() => team.value?.numericId ?? null)
const notification = useNotification()
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const { userTimezone } = useDatetime()

const activeTab = ref(0)
const showCreateDialog = ref(false)
const showRequestDialog = ref(false)
const selectedScheduleId = ref<number | null>(null)

// シフト表作成フォーム
const createForm = ref({
  title: '',
  periodStart: null as Date | null,
  periodEnd: null as Date | null,
})
const creating = ref(false)

async function createSchedule() {
  if (
    !createForm.value.title.trim() ||
    !createForm.value.periodStart ||
    !createForm.value.periodEnd
  )
    return
  creating.value = true
  try {
    await shiftApi.createSchedule(teamSlug, {
      title: createForm.value.title.trim(),
      startDate: dayjs(createForm.value.periodStart).tz(userTimezone.value).format('YYYY-MM-DD'),
      endDate: dayjs(createForm.value.periodEnd).tz(userTimezone.value).format('YYYY-MM-DD'),
    })
    notification.success('シフト表を作成しました')
    showCreateDialog.value = false
    createForm.value = { title: '', periodStart: null, periodEnd: null }
  } catch {
    notification.error('作成に失敗しました')
  } finally {
    creating.value = false
  }
}

function onScheduleSelect(id: number) {
  selectedScheduleId.value = id
  showRequestDialog.value = true
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <!-- ネストされたページ（ボード・変更依頼）が存在する場合はそちらに委譲 -->
    <NuxtPage v-if="$route.params.scheduleId" />
    <template v-else>
    <PageHeader title="シフト管理" class="mb-4" />

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">シフト表</Tab>
        <Tab :value="1">シフト交換</Tab>
        <Tab :value="2">ポジション管理</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <ShiftScheduleList
            :team-id="teamSlug"
            :can-manage="isAdminOrDeputy"
            @select="onScheduleSelect"
            @create="showCreateDialog = true"
          />
        </TabPanel>
        <TabPanel :value="1">
          <!-- 交代申請APIは数値 teamId を要求する。解決前は骨組みを出して誤リクエストを撃たない -->
          <ShiftSwapList v-if="teamNumericId !== null" :team-id="teamNumericId" />
          <div v-else>
            <Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" />
          </div>
        </TabPanel>
        <TabPanel v-if="isAdmin" :value="2">
          <ShiftPositionManager :team-id="teamSlug" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- シフト表作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="シフト表を作成"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル</label>
          <InputText v-model="createForm.title" class="w-full" placeholder="例: 2026年4月第1週" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日</label>
            <DatePicker
              v-model="createForm.periodStart"
              date-format="yy/mm/dd"
              class="w-full"
              show-icon
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日</label>
            <DatePicker
              v-model="createForm.periodEnd"
              date-format="yy/mm/dd"
              class="w-full"
              show-icon
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" :loading="creating" @click="createSchedule" />
      </template>
    </Dialog>

    <!-- シフト希望提出ダイアログ -->
    <ShiftRequestForm
      v-if="selectedScheduleId"
      v-model:visible="showRequestDialog"
      :team-id="teamSlug"
      :schedule-id="selectedScheduleId"
    />
    </template>
  </div>
</template>
