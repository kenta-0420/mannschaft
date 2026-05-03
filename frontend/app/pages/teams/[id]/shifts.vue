<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const shiftApi = useShiftApi()
const notification = useNotification()
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

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
    await shiftApi.createSchedule(teamId, {
      title: createForm.value.title.trim(),
      startDate: createForm.value.periodStart.toISOString().split('T')[0] ?? '',
      endDate: createForm.value.periodEnd.toISOString().split('T')[0] ?? '',
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
            :team-id="teamId"
            :can-manage="isAdminOrDeputy"
            @select="onScheduleSelect"
            @create="showCreateDialog = true"
          />
        </TabPanel>
        <TabPanel :value="1">
          <ShiftSwapList :team-id="teamId" />
        </TabPanel>
        <TabPanel v-if="isAdmin" :value="2">
          <ShiftPositionManager :team-id="teamId" />
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
      :team-id="teamId"
      :schedule-id="selectedScheduleId"
    />
    </template>
  </div>
</template>
