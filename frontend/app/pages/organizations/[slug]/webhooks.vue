<script setup lang="ts">
/**
 * Webhook / APIキー管理。
 *
 * BE の Webhook 系 API はスコープを数値 ID で受ける（`scopeId` は Long）。このページは
 * 永続シェルのタブ対象外（`SHELL_SEGMENTS` に無い）ため親シェルの `org`（numericId 保持）が
 * ロードされている保証が無く、直接 URL 遷移時は未ロードのまま。そのため自前で
 * `useOrgDetail` を呼び、slug から数値 ID（`numericId`）を解決してから渡す
 * （実機確認済み: slug をそのまま渡すと `GET /api/v1/webhooks/endpoints` が 400 になる）。
 */
definePageMeta({ layout: 'organization', middleware: ['auth', 'org-role-guard'] })

const route = useRoute()
const router = useRouter()
const orgSlug = computed(() => String(route.params.slug))

const { org, fetchOrg } = useOrgDetail(orgSlug)
const scopeId = computed(() => org.value?.numericId ? String(org.value.numericId) : '')

onMounted(() => {
  if (!org.value) void fetchOrg()
})

// ===== タブ =====
const activeTab = ref(0)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">Webhook / 外部API管理</h1>
    </div>

    <Tabs v-if="scopeId" v-model:value="activeTab">
      <TabList>
        <Tab :value="0">送信Webhook</Tab>
        <Tab :value="1">受信Webhook</Tab>
        <Tab :value="2">APIキー</Tab>
      </TabList>

      <TabPanels>
        <TabPanel :value="0">
          <WebhookOutgoingTab scope-type="ORGANIZATION" :scope-id="scopeId" />
        </TabPanel>

        <TabPanel :value="1">
          <WebhookIncomingTab scope-type="ORGANIZATION" :scope-id="scopeId" />
        </TabPanel>

        <TabPanel :value="2">
          <WebhookApiKeyTab scope-type="ORGANIZATION" :scope-id="scopeId" />
        </TabPanel>
      </TabPanels>
    </Tabs>
    <div v-else class="flex justify-center py-12">
      <LoadingBounce />
    </div>
  </div>
</template>
