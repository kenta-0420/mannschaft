<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

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
      <PageHeader title="順番待ち" />
      <Button label="受付する" icon="pi pi-ticket" @click="showTicketForm = true" />
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">待ち状況</Tab>
        <Tab :value="1">窓口操作</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <QueueStatusBoard :team-id="teamSlug" />
        </TabPanel>
        <TabPanel v-if="isAdminOrDeputy" :value="1">
          <QueueAdminPanel :team-id="teamSlug" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <QueueTicketForm v-model:visible="showTicketForm" :team-id="teamSlug" @issued="onTicketIssued" />
  </div>
</template>
