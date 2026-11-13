<script setup lang="ts">
import type { MemberCard } from '~/types/member-card'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const memberCardApi = useMemberCardApi()
const notification = useNotification()

const cards = ref<MemberCard[]>([])
const loading = ref(true)
const selectedCard = ref<MemberCard | null>(null)
const activeTab = ref('0')

async function loadCards() {
  loading.value = true
  try {
    cards.value = await memberCardApi.listMy()
    const current = cards.value.find(c => c.id === selectedCard.value?.id)
    selectedCard.value = current ?? cards.value[0] ?? null
  } catch {
    notification.error(t('memberCardDisplay.fetchFailed'))
  } finally {
    loading.value = false
  }
}

async function handleSuspend(id: number) {
  try {
    await memberCardApi.suspend(id)
    notification.success(t('memberCardDisplay.suspendSuccess'))
    await loadCards()
  } catch {
    notification.error(t('memberCardDisplay.suspendFailed'))
  }
}

async function handleReactivate(id: number) {
  try {
    await memberCardApi.reactivate(id)
    notification.success(t('memberCardDisplay.reactivateSuccess'))
    await loadCards()
  } catch {
    notification.error(t('memberCardDisplay.reactivateFailed'))
  }
}

function handleSelect(card: MemberCard) {
  selectedCard.value = card
  activeTab.value = '1'
}

onMounted(loadCards)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader :title="t('memberCardDisplay.pageTitle')" />

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab" class="fade-in">
        <TabList>
          <Tab value="0">{{ t('memberCardDisplay.tabList') }}</Tab>
          <Tab value="1" :disabled="!selectedCard">{{ t('memberCardDisplay.tabHistory') }}</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <MemberCardList
              :cards="cards"
              :selected-id="selectedCard?.id ?? null"
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
