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
      <h1 class="text-2xl font-bold">順番待ち</h1>
      <Button label="受付する" icon="pi pi-ticket" @click="showTicketForm = true" />
    </div>

    <TabView v-model:active-index="activeTab">
      <TabPanel header="待ち状況">
        <QueueStatusBoard scope-type="organization" :scope-id="orgId" />
      </TabPanel>
      <TabPanel v-if="isAdminOrDeputy" header="窓口操作">
        <QueueAdminPanel scope-type="organization" :scope-id="orgId" />
      </TabPanel>
    </TabView>

    <QueueTicketForm
      v-model:visible="showTicketForm"
      scope-type="organization"
      :scope-id="orgId"
    />
  </div>
</template>
