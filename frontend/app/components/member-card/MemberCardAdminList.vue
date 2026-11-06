<script setup lang="ts">
import type { MemberCardListItem, MemberCardStatus } from '~/types/member-card'
const props = defineProps<{ cards: MemberCardListItem[]; busy: boolean }>()
const emit = defineEmits<{ select: [card: MemberCardListItem]; suspend: [id: number]; reactivate: [id: number] }>()
const { t } = useI18n()
const status = ref<MemberCardStatus | 'ALL'>('ALL')
const options = computed(() => ['ALL', 'ACTIVE', 'SUSPENDED', 'REVOKED'].map(value => ({ label: t(`memberCardAdmin.status.${value}`), value })))
const filtered = computed(() => props.cards.filter(card => !status.value || status.value === 'ALL' || card.status === status.value))
</script>

<template>
  <div>
    <SelectButton v-model="status" :options="options" option-label="label" option-value="value" class="mb-4 flex flex-wrap" :pt="{ pcToggleButton: { root: { class: 'min-h-11 min-w-11' } } }" />
    <DashboardEmptyState v-if="!filtered.length" icon="pi pi-id-card" :message="$t('memberCardAdmin.empty')" />
    <div v-else class="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
      <SectionCard v-for="card in filtered" :key="card.id" :title="card.displayName">
        <p class="mb-2 font-mono text-sm">{{ card.cardNumber }}</p>
        <Badge :value="$t(`memberCardAdmin.status.${card.status}`)" :severity="card.status === 'ACTIVE' ? 'success' : card.status === 'SUSPENDED' ? 'warn' : 'danger'" />
        <p class="my-3 text-sm">{{ $t('memberCardAdmin.count', { count: card.checkinCount }) }}</p>
        <div class="flex flex-wrap gap-2">
          <Button :label="$t('memberCardAdmin.history')" size="small" class="min-h-11 min-w-11" @click="emit('select', card)" />
          <Button v-if="card.status === 'ACTIVE'" :label="$t('memberCardAdmin.suspend')" severity="warn" size="small" class="min-h-11 min-w-11" :disabled="busy" @click="emit('suspend', card.id)" />
          <Button v-if="card.status === 'SUSPENDED'" :label="$t('memberCardAdmin.reactivate')" severity="success" size="small" class="min-h-11 min-w-11" :disabled="busy" @click="emit('reactivate', card.id)" />
        </div>
      </SectionCard>
    </div>
  </div>
</template>
