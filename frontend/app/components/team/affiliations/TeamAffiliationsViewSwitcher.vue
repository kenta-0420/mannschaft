<script setup lang="ts">
import type { TeamAffiliationsView } from '~/utils/teamAffiliationsView'

/**
 * F01.2.1 8-D — チーム「所属組織」画面の view 切替（所属組織 / 受信した招待 / 申請中 / 招待の制限）。
 * 切替はクエリ `view` で行い、子ルートは作らない（設計書 §13）。
 */
defineProps<{
  current: TeamAffiliationsView
  /** 返事待ちの受信招待の件数。未取得は null。 */
  inviteCount: number | null
  /** 件数の取得に失敗した。 */
  countError: boolean
}>()

const emit = defineEmits<{
  select: [view: TeamAffiliationsView]
}>()

const tabs: { view: TeamAffiliationsView, labelKey: string }[] = [
  { view: 'organizations', labelKey: 'teamAffiliations.view.organizations' },
  { view: 'invites', labelKey: 'teamAffiliations.view.invites' },
  { view: 'applications', labelKey: 'teamAffiliations.view.applications' },
  { view: 'restrictions', labelKey: 'teamAffiliations.view.restrictions' },
]
</script>

<template>
  <nav
    class="flex flex-wrap gap-2"
    :aria-label="$t('teamAffiliations.switcher_label')"
    data-testid="team-affiliations-switcher"
  >
    <button
      v-for="tab in tabs"
      :key="tab.view"
      type="button"
      class="inline-flex items-center gap-2 rounded-full border px-4 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-primary-400"
      :class="current === tab.view
        ? 'border-primary-500 bg-primary-50 text-primary-700 dark:bg-primary-900/20 dark:text-primary-300'
        : 'border-surface-300 bg-surface-0 text-surface-700 hover:bg-surface-100 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-200'"
      :aria-current="current === tab.view ? 'page' : undefined"
      :data-testid="`view-tab-${tab.view}`"
      @click="emit('select', tab.view)"
    >
      {{ $t(tab.labelKey) }}
      <template v-if="tab.view === 'invites'">
        <span
          v-if="inviteCount !== null && inviteCount > 0"
          class="rounded-full bg-red-500 px-2 text-xs font-bold text-white"
          data-testid="view-tab-badge"
        >{{ inviteCount }}</span>
        <span
          v-else-if="countError"
          class="rounded-full bg-amber-500 px-2 text-xs font-bold text-white"
          :title="$t('teamAffiliations.count_error')"
          data-testid="view-tab-badge-error"
        >!</span>
      </template>
    </button>
  </nav>
</template>
