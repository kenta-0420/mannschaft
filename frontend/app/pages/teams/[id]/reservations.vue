<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const activeTab = ref(0)
const showBookDialog = ref(false)
const selectedSlot = ref({ slotId: 0, lineName: '', date: '', startTime: '', endTime: '' })

function onSlotSelected(
  slotId: number,
  lineName: string,
  date: string,
  startTime: string,
  endTime: string,
) {
  selectedSlot.value = { slotId, lineName, date, startTime, endTime }
  showBookDialog.value = true
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <h1 class="mb-4 text-2xl font-bold">予約管理</h1>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">予約する</Tab>
        <Tab :value="1">予約一覧</Tab>
        <Tab :value="2">ライン管理</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <SlotPicker :team-id="teamId" @slot-selected="onSlotSelected" />
        </TabPanel>
        <TabPanel :value="1">
          <ReservationList :team-id="teamId" :can-manage="isAdminOrDeputy" />
        </TabPanel>
        <TabPanel v-if="isAdmin" :value="2">
          <LineManager :team-id="teamId" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <ReservationForm
      v-model:visible="showBookDialog"
      :team-id="teamId"
      :slot-id="selectedSlot.slotId"
      :line-name="selectedSlot.lineName"
      :date="selectedSlot.date"
      :start-time="selectedSlot.startTime"
      :end-time="selectedSlot.endTime"
    />
  </div>
</template>
