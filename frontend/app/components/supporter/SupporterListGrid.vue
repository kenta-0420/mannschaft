<script setup lang="ts">
import type { SupporterItem } from '~/composables/useSupporterManagement'

defineProps<{
  supporters: SupporterItem[]
  loading: boolean
}>()

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('ja-JP')
}
</script>

<template>
  <div class="rounded-lg border p-4">
    <h3 class="mb-3 font-semibold">
      サポーター一覧
      <span class="ml-1 text-sm font-normal text-gray-500">（{{ supporters.length }}人）</span>
    </h3>
    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>
    <div
      v-else-if="supporters.length === 0"
      class="rounded-lg border border-dashed border-gray-300 py-8 text-center text-sm text-gray-500"
    >
      <i class="pi pi-heart mb-2 text-2xl" />
      <p>まだサポーターがいません</p>
    </div>
    <div v-else class="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="supporter in supporters"
        :key="supporter.userId"
        class="flex items-center gap-3 rounded-lg border p-3"
      >
        <Avatar
          :image="supporter.avatarUrl ?? undefined"
          :label="supporter.avatarUrl ? undefined : supporter.displayName.charAt(0)"
          shape="circle"
        />
        <div class="min-w-0 flex-1">
          <p class="truncate font-medium">{{ supporter.displayName }}</p>
          <p class="text-xs text-gray-400">{{ formatDate(supporter.followedAt) }}から</p>
        </div>
      </div>
    </div>
  </div>
</template>
