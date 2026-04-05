<script setup lang="ts">
import type { MemberCard } from '~/types/member-card'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const memberCardApi = useMemberCardApi()
const notification = useNotification()
const { loadPermissions } = useRoleAccess('organization', orgId)

const cards = ref<MemberCard[]>([])
const loading = ref(true)
const selectedCard = ref<MemberCard | null>(null)
const activeTab = ref('0')

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    cards.value = await memberCardApi.listByOrg(orgId.value)
  } catch {
    notification.error('データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleSuspend(id: number) {
  try {
    await memberCardApi.suspend(id)
    notification.success('会員証を一時停止しました')
    await loadData()
  } catch {
    notification.error('一時停止に失敗しました')
  }
}

async function handleReactivate(id: number) {
  try {
    await memberCardApi.reactivate(id)
    notification.success('会員証を再開しました')
    await loadData()
  } catch {
    notification.error('再開に失敗しました')
  }
}

function handleSelect(card: MemberCard) {
  selectedCard.value = card
  activeTab.value = '1'
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">QR会員証管理</h1>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">会員証一覧</Tab>
          <Tab value="1" :disabled="!selectedCard">チェックイン履歴</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <MemberCardList
              :cards="cards"
              @select="handleSelect"
              @suspend="handleSuspend"
              @reactivate="handleReactivate"
            />
          </TabPanel>
          <TabPanel value="1">
            <CheckinHistory v-if="selectedCard" :card-id="selectedCard.id" />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
