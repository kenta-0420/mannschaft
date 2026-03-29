<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const activeTab = ref(0)
const showTicketForm = ref(false)

function onTicketIssued(_ticket: { ticketNumber: string }) {
  // リロードはQueueStatusBoardのポーリングで自動反映
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">順番待ち</h1>
      <Button label="受付する" icon="pi pi-ticket" @click="showTicketForm = true" />
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">待ち状況</Tab>
        <Tab :value="1">窓口操作</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <QueueStatusBoard scope-type="team" :scope-id="teamId" />
        </TabPanel>
        <TabPanel v-if="isAdminOrDeputy" :value="1">
          <QueueAdminPanel scope-type="team" :scope-id="teamId" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <QueueTicketForm
      v-model:visible="showTicketForm"
      scope-type="team"
      :scope-id="teamId"
      @issued="onTicketIssued"
    />
  </div>
</template>
