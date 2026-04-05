<script setup lang="ts">
import type { MemberCard } from '~/types/member-card'

defineProps<{
  memberCards: MemberCard[]
  selectedCard: MemberCard | null
  memberCardActiveTab: string
}>()

defineEmits<{
  selectCard: [card: MemberCard]
  suspendCard: [id: number]
  reactivateCard: [id: number]
  'update:memberCardActiveTab': [value: string]
}>()
</script>

<template>
  <SectionCard title="QR会員証">
    <Tabs
      :value="memberCardActiveTab"
      @update:value="$emit('update:memberCardActiveTab', $event as string)"
    >
      <TabList>
        <Tab value="0">会員証一覧</Tab>
        <Tab value="1" :disabled="!selectedCard">チェックイン履歴</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="0">
          <MemberCardList
            :cards="memberCards"
            @select="$emit('selectCard', $event)"
            @suspend="$emit('suspendCard', $event)"
            @reactivate="$emit('reactivateCard', $event)"
          />
        </TabPanel>
        <TabPanel value="1">
          <CheckinHistory v-if="selectedCard" :card-id="selectedCard.id" />
        </TabPanel>
      </TabPanels>
    </Tabs>
  </SectionCard>
</template>
