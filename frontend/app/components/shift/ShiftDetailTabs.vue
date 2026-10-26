<script setup lang="ts">
/**
 * /shift/{id} 系4ページ（概要・編集・希望一覧・勤務制約）が共有するタブ nav（CMP-261010-1432 AC-K1〜K3）。
 *
 * 4ページに複製されていた nav を1か所に寄せ、ボード・変更依頼・PDF への導線を足した。
 * 出し分けはここで完結させる（ページ側に判定を散らさない）:
 * - 希望一覧・勤務制約・ボード … 当該チームの ADMIN / DEPUTY_ADMIN のみ
 * - 変更依頼 … teams 配下のため、利用者の myTeams にそのチームがあるときだけ
 * - PDF … myTeams にあり、かつ SUPPORTER でないとき（board.vue と同じ規則）
 */
type TabKey = 'overview' | 'edit' | 'requests' | 'constraints'

const props = defineProps<{
  scheduleId: number
  /** シフト表のチーム ID。取得前・失敗時は null（teams 配下のリンクを出さない）。 */
  teamId: number | null
  /** 現在表示しているタブ（リンクにしない） */
  current: TabKey
}>()

const { t } = useI18n()
const teamStore = useTeamStore()

const team = computed(() =>
  props.teamId === null ? undefined : teamStore.myTeams.find(m => m.id === props.teamId),
)
const canManage = computed(() =>
  ['ADMIN', 'SYSTEM_ADMIN', 'DEPUTY_ADMIN'].includes(team.value?.role ?? ''),
)
/** シフトボードは ADMIN / DEPUTY_ADMIN のみ（ShiftScheduleList / board.vue と同じ規則） */
const canOpenBoard = computed(() => ['ADMIN', 'DEPUTY_ADMIN'].includes(team.value?.role ?? ''))
const showPdf = computed(() => team.value !== undefined && team.value.role !== 'SUPPORTER')

interface Tab {
  key: string
  label: string
  icon: string
  to: string
  current: boolean
}

const tabs = computed<Tab[]>(() => {
  const base = `/shift/${props.scheduleId}`
  const list: Tab[] = [
    { key: 'overview', label: t('shift.detail.tabOverview'), icon: 'pi pi-calendar', to: base, current: props.current === 'overview' },
    { key: 'edit', label: t('shift.detail.tabEdit'), icon: 'pi pi-pencil', to: `${base}/edit`, current: props.current === 'edit' },
  ]
  if (canManage.value) {
    list.push(
      { key: 'requests', label: t('shift.detail.tabRequests'), icon: 'pi pi-list', to: `${base}/requests`, current: props.current === 'requests' },
      { key: 'constraints', label: t('shift.detail.tabConstraints'), icon: 'pi pi-shield', to: `${base}/work-constraints`, current: props.current === 'constraints' },
    )
  }
  if (team.value) {
    const teamBase = `/teams/${team.value.slug}/shifts/${props.scheduleId}`
    if (canOpenBoard.value) {
      list.push({ key: 'board', label: t('shift.detail.tabBoard'), icon: 'pi pi-th-large', to: `${teamBase}/board`, current: false })
    }
    list.push({ key: 'change-requests', label: t('shift.detail.tabChangeRequests'), icon: 'pi pi-comments', to: `${teamBase}/change-requests`, current: false })
  }
  return list
})
</script>

<template>
  <nav
    class="mb-6 flex flex-wrap items-center gap-1 border-b border-surface-200 dark:border-surface-700"
  >
    <template v-for="tab in tabs" :key="tab.key">
      <span
        v-if="tab.current"
        :data-testid="`shift-tab-${tab.key}`"
        class="flex min-h-[44px] shrink-0 items-center gap-1.5 border-b-2 border-primary px-4 py-2 text-sm font-medium text-primary"
      >
        <i :class="tab.icon" />{{ tab.label }}
      </span>
      <NuxtLink
        v-else
        :to="tab.to"
        :data-testid="`shift-tab-${tab.key}`"
        class="flex min-h-[44px] shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i :class="tab.icon" />{{ tab.label }}
      </NuxtLink>
    </template>
    <div v-if="showPdf" data-testid="shift-tab-pdf" class="ml-auto shrink-0 py-1">
      <ShiftPdfExportButton :schedule-id="scheduleId" />
    </div>
  </nav>
</template>
