<script setup lang="ts">
import type { OrgPermissionGroup } from '~/types/organization'

defineProps<{
  groups: OrgPermissionGroup[]
}>()
</script>

<template>
  <div class="mt-4">
    <div
      v-if="groups.length === 0"
      class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500"
    >
      <i class="pi pi-shield mb-2 text-3xl" />
      <p>権限グループはまだ作成されていません</p>
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="group in groups"
        :key="group.id"
        class="rounded-lg border p-4"
      >
        <div class="mb-2 flex items-center justify-between">
          <h3 class="font-semibold">
            {{ group.name }}
          </h3>
          <span class="text-sm text-gray-500"
            >{{ group.permissions.length }}件の権限</span
          >
        </div>
        <p v-if="group.description" class="mb-2 text-sm text-gray-600">
          {{ group.description }}
        </p>
        <div class="flex flex-wrap gap-1">
          <Tag
            v-for="perm in group.permissions"
            :key="perm"
            :value="perm"
            severity="secondary"
            class="text-xs"
          />
        </div>
      </div>
    </div>
  </div>
</template>
