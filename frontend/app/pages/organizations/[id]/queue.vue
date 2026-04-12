<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const activeTab = ref(0)
const showTicketForm = ref(false)

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
          <QueueStatusBoard :team-id="orgId" />
        </TabPanel>
        <TabPanel v-if="isAdminOrDeputy" :value="1">
          <QueueAdminPanel :team-id="orgId" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <QueueTicketForm v-model:visible="showTicketForm" :team-id="orgId" />
  </div>
</template>
