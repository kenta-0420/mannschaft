<script setup lang="ts">
import type { MemberCardListItem } from '~/types/member-card'

const props = defineProps<{ scopeType: 'team' | 'organization'; slug: string }>()
const slug = toRef(props, 'slug')
const { cards, stats, scopeId, loading, loadError, busy, isAdminOrDeputy, loadData, changeStatus } = useMemberCardManagement(props.scopeType, slug)
const showGuide = ref(false)
const activeTab = ref('0')
const selectedCard = ref<MemberCardListItem | null>(null)

function selectCard(card: MemberCardListItem) {
  selectedCard.value = card
  activeTab.value = '1'
}

watch(slug, () => {
  selectedCard.value = null
  activeTab.value = '0'
  loadData()
})
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader :title="$t('memberCardAdmin.title')" class="[&_button]:min-h-11 [&_button]:min-w-11" help @help="showGuide = true" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="loadData" />
    <Tabs v-else v-model:value="activeTab">
      <TabList>
        <Tab value="0">{{ $t('memberCardAdmin.list') }}</Tab>
        <Tab value="1" :disabled="!selectedCard">{{ $t('memberCardAdmin.history') }}</Tab>
        <Tab v-if="stats" value="2">{{ $t('memberCardAdmin.stats') }}</Tab>
        <Tab v-if="scopeType === 'team' && isAdminOrDeputy" value="3">{{ $t('memberCardAdmin.locations') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="0">
          <MemberCardAdminList :cards="cards" :busy="busy" @select="selectCard" @suspend="changeStatus($event, 'suspend')" @reactivate="changeStatus($event, 'reactivate')" />
        </TabPanel>
        <TabPanel value="1">
          <CheckinHistory v-if="selectedCard" :key="selectedCard.id" :card-id="selectedCard.id" />
        </TabPanel>
        <TabPanel v-if="stats" value="2">
          <p class="mb-4 text-sm text-surface-500">{{ $t('memberCardAdmin.period', { from: stats.period?.from, to: stats.period?.to }) }}</p>
          <div class="grid gap-4 md:grid-cols-3">
            <SectionCard :title="$t('memberCardAdmin.total')"><p class="text-3xl font-bold">{{ stats.totalCheckins }}</p></SectionCard>
            <SectionCard :title="$t('memberCardAdmin.unique')"><p class="text-3xl font-bold">{{ stats.uniqueMembers }}</p></SectionCard>
            <SectionCard :title="$t('memberCardAdmin.byDay')">
              <div v-for="item in stats.byDayOfWeek" :key="item.day" class="flex justify-between text-sm">
                <span>{{ $t(`memberCardAdmin.days.${item.day}`) }}</span><span>{{ item.count }}</span>
              </div>
            </SectionCard>
          </div>
        </TabPanel>
        <TabPanel v-if="scopeType === 'team' && isAdminOrDeputy" value="3">
          <CheckinLocationManager :key="scopeId" :team-id="scopeId" />
        </TabPanel>
      </TabPanels>
    </Tabs>
    <MemberCardGuideModal v-model:visible="showGuide" :team="scopeType === 'team'" />
  </div>
</template>
