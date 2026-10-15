<script setup lang="ts">
import type { MemberCard, MemberCardStatus } from '~/types/member-card'

defineProps<{
  cards: MemberCard[]
}>()

const emit = defineEmits<{
  select: [card: MemberCard]
  suspend: [id: number]
  reactivate: [id: number]
}>()

const statusFilter = ref<MemberCardStatus | 'ALL'>('ALL')

const statusOptions = [
  { label: 'すべて', value: 'ALL' },
  { label: '有効', value: 'ACTIVE' },
  { label: '停止中', value: 'SUSPENDED' },
  { label: '無効', value: 'REVOKED' },
]
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-2">
      <SelectButton v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" />
    </div>

    <div v-if="cards.length === 0" class="py-12 text-center text-surface-500">
      <i class="pi pi-id-card mb-2 text-4xl" />
      <p>会員証がありません</p>
    </div>

    <div v-else class="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="card in cards.filter(c => statusFilter === 'ALL' || c.status === statusFilter)"
        :key="card.id"
        class="cursor-pointer"
        @click="emit('select', card)"
      >
        <MemberCardDisplay
          :card="card"
          @suspend="emit('suspend', $event)"
          @reactivate="emit('reactivate', $event)"
        />
      </div>
    </div>
  </div>
</template>
