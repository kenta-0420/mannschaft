<script setup lang="ts">
import type { OrgTeam } from '~/types/organization'

defineProps<{
  teams: OrgTeam[]
}>()

const { templateLabel } = useScopeLabels()
</script>

<template>
  <div class="mt-4">
    <div
      v-if="teams.length === 0"
      class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500"
    >
      <i class="pi pi-inbox mb-2 text-3xl" />
      <p>所属チームはありません</p>
    </div>
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="team in teams"
        :key="team.id"
        class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
        @click="navigateTo(`/teams/${team.id}`)"
      >
        <div class="mb-2 flex items-center gap-3">
          <Avatar
            :image="team.iconUrl ?? undefined"
            :label="team.iconUrl ? undefined : team.name.charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <h3 class="truncate font-semibold">
              {{ team.nickname1 || team.name }}
            </h3>
            <Tag
              :value="templateLabel[team.template] ?? team.template"
              severity="info"
              class="text-xs"
            />
          </div>
        </div>
        <div class="text-sm text-gray-500">
          <i class="pi pi-users mr-1" />{{ team.memberCount }}人
        </div>
      </div>
    </div>
  </div>
</template>
