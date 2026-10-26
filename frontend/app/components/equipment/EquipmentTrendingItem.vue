<script setup lang="ts">
import type { EquipmentTrendingItem } from '~/types/equipment-ranking'

defineProps<{ item: EquipmentTrendingItem }>()
</script>

<template>
  <div class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3">
    <span class="w-6 shrink-0 text-center text-sm font-bold text-surface-400">{{ item.rank }}</span>
    <div class="min-w-0 flex-1">
      <p class="truncate text-sm font-medium">{{ item.itemName }}</p>
      <p class="text-xs text-surface-400">
        {{ $t('equipment.trending.team_count', { count: item.teamCount }) }}
        <span v-if="item.consumeEventCount > 0">
          ・ {{ $t('equipment.trending.consume_count', { count: item.consumeEventCount }) }}
        </span>
      </p>
    </div>
    <a
      v-if="item.replenishUrl"
      :href="item.replenishUrl"
      target="_blank"
      rel="noopener noreferrer sponsored"
      class="shrink-0 rounded-md bg-amber-400 px-2 py-1 text-xs font-medium text-amber-900 hover:bg-amber-500 transition-colors"
    >
      {{ $t('equipment.trending.amazon_link_label') }}
    </a>
  </div>
</template>
