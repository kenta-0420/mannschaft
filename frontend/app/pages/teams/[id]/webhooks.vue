<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = String(route.params.id)

// ===== タブ =====
const activeTab = ref(0)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="Webhook / 外部API管理" />
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">送信Webhook</Tab>
        <Tab :value="1">受信Webhook</Tab>
        <Tab :value="2">APIキー</Tab>
      </TabList>

      <TabPanels>
        <TabPanel :value="0">
          <WebhookOutgoingTab scope-type="TEAM" :scope-id="teamId" />
        </TabPanel>

        <TabPanel :value="1">
          <WebhookIncomingTab scope-type="TEAM" :scope-id="teamId" />
        </TabPanel>

        <TabPanel :value="2">
          <WebhookApiKeyTab scope-type="TEAM" :scope-id="teamId" />
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>
