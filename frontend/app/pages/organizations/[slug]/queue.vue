<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

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
          <QueueStatusBoard :team-id="orgSlug" />
        </TabPanel>
        <TabPanel v-if="isAdminOrDeputy" :value="1">
          <QueueAdminPanel :team-id="orgSlug" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <QueueTicketForm v-model:visible="showTicketForm" :team-id="orgSlug" />
  </div>
</template>
