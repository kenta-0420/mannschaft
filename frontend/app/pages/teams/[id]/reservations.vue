<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const activeTab = ref(0)
const showBookDialog = ref(false)
const selectedSlot = ref({ slotId: 0, lineName: '', date: '', startTime: '', endTime: '' })

function onSlotSelected(slotId: number, lineName: string, date: string, startTime: string, endTime: string) {
  selectedSlot.value = { slotId, lineName, date, startTime, endTime }
  showBookDialog.value = true
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <h1 class="mb-4 text-2xl font-bold">予約管理</h1>

    <TabView v-model:active-index="activeTab">
      <TabPanel header="予約する">
        <SlotPicker :team-id="teamId" @slot-selected="onSlotSelected" />
      </TabPanel>
      <TabPanel header="予約一覧">
        <ReservationList :team-id="teamId" :can-manage="isAdminOrDeputy" />
      </TabPanel>
      <TabPanel v-if="isAdmin" header="ライン管理">
        <LineManager :team-id="teamId" />
      </TabPanel>
    </TabView>

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
