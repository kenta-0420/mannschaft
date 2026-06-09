<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

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
    <PageHeader :title="t('reservation.page.team_title')" class="mb-4" />

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">{{ t('reservation.tab.book') }}</Tab>
        <Tab :value="1">{{ t('reservation.tab.list') }}</Tab>
        <Tab :value="2">{{ t('reservation.tab.line_manage') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <SlotPicker :team-id="teamSlug" @slot-selected="onSlotSelected" />
        </TabPanel>
        <TabPanel :value="1">
          <ReservationList :team-id="teamSlug" :can-manage="isAdminOrDeputy" />
        </TabPanel>
        <TabPanel v-if="isAdmin" :value="2">
          <LineManager :team-id="teamSlug" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <ReservationForm
      v-model:visible="showBookDialog"
      :team-id="teamSlug"
      :slot-id="selectedSlot.slotId"
      :line-name="selectedSlot.lineName"
      :date="selectedSlot.date"
      :start-time="selectedSlot.startTime"
      :end-time="selectedSlot.endTime"
    />
  </div>
</template>
