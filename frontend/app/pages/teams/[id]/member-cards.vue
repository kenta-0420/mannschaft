<script setup lang="ts">
import type { MemberCard, CheckinStats } from '~/types/member-card'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const memberCardApi = useMemberCardApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const cards = ref<MemberCard[]>([])
const stats = ref<CheckinStats | null>(null)
const loading = ref(true)
const activeTab = ref('0')
const selectedCard = ref<MemberCard | null>(null)

async function loadData() {
  loading.value = true
  try {
    const [cardsRes, perms] = await Promise.all([
      memberCardApi.listByTeam(teamId.value),
      loadPermissions(),
    ])
    cards.value = cardsRes
    if (isAdmin.value) {
      stats.value = await memberCardApi.getTeamCheckinStats(teamId.value)
    }
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

    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">会員証一覧</Tab>
          <Tab value="1" :disabled="!selectedCard">チェックイン履歴</Tab>
          <Tab v-if="isAdmin && stats" value="2">チェックイン統計</Tab>
          <Tab v-if="isAdmin" value="3">拠点管理</Tab>
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
          <TabPanel v-if="isAdmin && stats" value="2">
            <div class="grid gap-4 md:grid-cols-3">
              <Card>
                <template #content>
                  <p class="text-sm text-surface-500">総チェックイン数</p>
                  <p class="text-3xl font-bold text-primary">{{ stats.totalCheckins }}</p>
                </template>
              </Card>
              <Card>
                <template #title>曜日別</template>
                <template #content>
                  <div v-for="(count, day) in stats.byDayOfWeek" :key="day" class="flex justify-between text-sm">
                    <span>{{ day }}</span>
                    <span class="font-medium">{{ count }}</span>
                  </div>
                </template>
              </Card>
              <Card>
                <template #title>月間推移</template>
                <template #content>
                  <div v-for="item in stats.monthlyTrend" :key="item.month" class="flex justify-between text-sm">
                    <span>{{ item.month }}</span>
                    <span class="font-medium">{{ item.count }}</span>
                  </div>
                </template>
              </Card>
            </div>
          </TabPanel>
          <TabPanel v-if="isAdmin" value="3">
            <CheckinLocationManager :team-id="teamId" />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
