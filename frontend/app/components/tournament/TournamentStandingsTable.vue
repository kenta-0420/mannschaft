<script setup lang="ts">
import type { TournamentStanding } from '~/types/tournament'

defineProps<{ standings: TournamentStanding[] }>()
</script>

<template>
  <div class="overflow-x-auto rounded-xl border border-surface-200">
    <table class="w-full text-sm">
      <thead class="bg-surface-50">
        <tr>
          <th class="px-3 py-2 text-left">#</th>
          <th class="px-3 py-2 text-left">チーム</th>
          <th class="px-3 py-2 text-center">試合</th>
          <th class="px-3 py-2 text-center">勝</th>
          <th class="px-3 py-2 text-center">分</th>
          <th class="px-3 py-2 text-center">負</th>
          <th class="px-3 py-2 text-center">得点</th>
          <th class="px-3 py-2 text-center">失点</th>
          <th class="px-3 py-2 text-center">得失差</th>
          <th class="px-3 py-2 text-center font-bold">勝点</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in standings" :key="s.teamId" class="border-t border-surface-100">
          <td class="px-3 py-2 font-medium">{{ s.rank }}</td>
          <td class="px-3 py-2">
            <div class="flex items-center gap-2">
              <img v-if="s.teamLogoUrl" :src="s.teamLogoUrl" class="h-5 w-5 rounded-full" />
              <span>{{ s.teamName }}</span>
            </div>
          </td>
          <td class="px-3 py-2 text-center">{{ s.played }}</td>
          <td class="px-3 py-2 text-center">{{ s.won }}</td>
          <td class="px-3 py-2 text-center">{{ s.drawn }}</td>
          <td class="px-3 py-2 text-center">{{ s.lost }}</td>
          <td class="px-3 py-2 text-center">{{ s.goalsFor }}</td>
          <td class="px-3 py-2 text-center">{{ s.goalsAgainst }}</td>
          <td class="px-3 py-2 text-center" :class="s.goalDifference > 0 ? 'text-green-600' : s.goalDifference < 0 ? 'text-red-500' : ''">{{ s.goalDifference > 0 ? '+' : '' }}{{ s.goalDifference }}</td>
          <td class="px-3 py-2 text-center font-bold text-primary">{{ s.points }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
