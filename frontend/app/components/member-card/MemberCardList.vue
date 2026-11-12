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

const { t } = useI18n()
const statusFilter = ref<MemberCardStatus | 'ALL'>('ALL')

const statusOptions = computed(() =>
  (['ALL', 'ACTIVE', 'SUSPENDED', 'REVOKED'] as const).map(value => ({
    label: t(`memberCardDisplay.status.${value}`),
    value,
  })),
)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-2">
      <SelectButton v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" />
    </div>

    <div v-if="cards.length === 0" class="py-12 text-center text-surface-500">
      <i class="pi pi-id-card mb-2 text-4xl" />
      <p>{{ $t('memberCardDisplay.empty') }}</p>
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
