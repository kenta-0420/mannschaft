<script setup lang="ts">
import type { MemberTeamsView } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — member-teams の view 切替（一覧 / 加盟申請 / 送った招待 / 申請の制限）。
 *
 * 組織 ADMIN・管理レンズ ON のときだけ親が描画する。groups（チームグループ）は 8-C で追加。
 * 切替はクエリ `view` で行い、子ルートは作らない（設計書 §13）。
 */
defineProps<{
  current: MemberTeamsView
  /** 未処理の加盟申請件数。未取得は null。 */
  pendingCount: number | null
  /** 件数の取得に失敗した。 */
  countError: boolean
}>()

const emit = defineEmits<{
  select: [view: MemberTeamsView]
}>()

const tabs: { view: MemberTeamsView, labelKey: string }[] = [
  { view: 'teams', labelKey: 'teamAffiliation.view.teams' },
  { view: 'applications', labelKey: 'teamAffiliation.view.applications' },
  { view: 'invites', labelKey: 'teamAffiliation.view.invites' },
  { view: 'restrictions', labelKey: 'teamAffiliation.view.restrictions' },
  { view: 'groups', labelKey: 'teamAffiliation.view.groups' },
]
</script>

<template>
  <nav
    class="mt-4 flex flex-wrap gap-2"
    :aria-label="$t('orgMemberTeams.switcher_label')"
    data-testid="member-teams-view-switcher"
  >
    <button
      v-for="tab in tabs"
      :key="tab.view"
      type="button"
      class="inline-flex min-h-11 min-w-11 items-center gap-2 rounded-full border px-4 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-primary-400"
      :class="current === tab.view
        ? 'border-primary-500 bg-primary-50 text-primary-700 dark:bg-primary-900/20 dark:text-primary-300'
        : 'border-surface-300 bg-surface-0 text-surface-700 hover:bg-surface-100 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-200'"
      :aria-current="current === tab.view ? 'page' : undefined"
      :data-testid="`view-tab-${tab.view}`"
      @click="emit('select', tab.view)"
    >
      {{ $t(tab.labelKey) }}
      <template v-if="tab.view === 'applications'">
        <span
          v-if="pendingCount !== null && pendingCount > 0"
          class="rounded-full bg-red-500 px-2 text-xs font-bold text-white"
          data-testid="view-tab-badge"
        >{{ pendingCount }}</span>
        <span
          v-else-if="countError"
          class="rounded-full bg-amber-500 px-2 text-xs font-bold text-white"
          :title="$t('orgMemberTeams.count_error')"
          data-testid="view-tab-badge-error"
        >!</span>
      </template>
    </button>
  </nav>
</template>
