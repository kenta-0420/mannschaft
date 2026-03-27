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

    <TabView v-model:active-index="activeTab">
      <TabPanel header="待ち状況">
        <QueueStatusBoard scope-type="team" :scope-id="teamId" />
      </TabPanel>
      <TabPanel v-if="isAdminOrDeputy" header="窓口操作">
        <QueueAdminPanel scope-type="team" :scope-id="teamId" />
      </TabPanel>
    </TabView>

    <QueueTicketForm
      v-model:visible="showTicketForm"
      scope-type="team"
      :scope-id="teamId"
      @issued="onTicketIssued"
    />
  </div>
</template>
