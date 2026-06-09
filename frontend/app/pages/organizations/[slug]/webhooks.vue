<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const orgId = String(route.params.id)

// ===== タブ =====
const activeTab = ref(0)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">Webhook / 外部API管理</h1>
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">送信Webhook</Tab>
        <Tab :value="1">受信Webhook</Tab>
        <Tab :value="2">APIキー</Tab>
      </TabList>

      <TabPanels>
        <TabPanel :value="0">
          <WebhookOutgoingTab scope-type="ORGANIZATION" :scope-id="orgId" />
        </TabPanel>

        <TabPanel :value="1">
          <WebhookIncomingTab scope-type="ORGANIZATION" :scope-id="orgId" />
        </TabPanel>

        <TabPanel :value="2">
          <WebhookApiKeyTab scope-type="ORGANIZATION" :scope-id="orgId" />
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>
