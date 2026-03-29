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
    await shiftApi.createShiftSchedule({
      title: createForm.value.title.trim(),
      periodStart: createForm.value.periodStart.toISOString().split('T')[0],
      periodEnd: createForm.value.periodEnd.toISOString().split('T')[0],
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
    <h1 class="mb-4 text-2xl font-bold">シフト管理</h1>

    <TabView v-model:active-index="activeTab">
      <TabPanel header="シフト表">
        <ShiftScheduleList
          :team-id="teamId"
          :can-manage="isAdminOrDeputy"
          @select="onScheduleSelect"
          @create="showCreateDialog = true"
        />
      </TabPanel>
      <TabPanel header="シフト交換">
        <ShiftSwapList :team-id="teamId" />
      </TabPanel>
      <TabPanel v-if="isAdmin" header="ポジション管理">
        <ShiftPositionManager :team-id="teamId" />
      </TabPanel>
    </TabView>

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
  </div>
</template>
