<script setup lang="ts">
import type { ShiftScheduleResponse } from '~/types/shift'
import { isAcceptingChangeRequests } from '~/utils/shiftStatus'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const scheduleId = computed(() => Number(route.params.scheduleId))

const authStore = useAuthStore()
const { getSchedule } = useShiftApi()
const { getTeam } = useTeamApi()

// 審査してよいか（チーム ADMIN / DEPUTY_ADMIN）。BE（ShiftChangeRequestFacade）の判定に揃える。
// プラットフォームの systemRole ではなく、このチームでの実効ロールで判定する。
// 権限取得に失敗したときは roleName が null のままなので審査ボタンは出ない（権限なしに倒れる側）。
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const {
  requests,
  isLoading,
  processingIds,
  loadError: listError,
  fetchRequests,
  review,
  withdraw,
} = useChangeRequest(scheduleId)

const schedule = ref<ShiftScheduleResponse | null>(null)
const loading = ref(true)
/** 初回取得の失敗 */
const initialError = ref<unknown>(null)
/**
 * 取得失敗（空状態とは区別して DashboardErrorState で出す）。
 * 初回取得に加え、作成・審査・取り下げ後の一覧再取得の失敗もここに載る。再試行は初回と同じ load。
 */
const loadError = computed(() => initialError.value ?? listError.value)

const canCreate = computed(() =>
  schedule.value ? isAcceptingChangeRequests(schedule.value.status.status) : false,
)

async function load(): Promise<void> {
  loading.value = true
  initialError.value = null
  try {
    const [scheduleRes, teamRes, fetchFailure, permissions] = await Promise.all([
      getSchedule(scheduleId.value),
      getTeam(teamSlug.value),
      fetchRequests(),
      loadPermissions(),
    ])
    if (fetchFailure) {
      initialError.value = fetchFailure
      return
    }
    // 権限取得の失敗を捨てない。審査ボタンは出さないまま、取得エラーと再試行を見せる。
    if (!permissions.ok) {
      initialError.value = permissions.error
      return
    }
    // URL の slug のチームとシフト表のチームが食い違う画面は存在しないものとして扱う
    // （他チームのシフト表 ID を slug 違いで開かせない。存在オラクルを作らないため 404 相当）。
    const urlTeamId = teamRes.data.numericId
    if (urlTeamId === undefined) {
      initialError.value = new Error('team_numeric_id_missing')
      return
    }
    if (scheduleRes.teamId !== urlTeamId) {
      initialError.value = { statusCode: 404 }
      return
    }
    schedule.value = scheduleRes
  } catch (error) {
    initialError.value = error
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onSubmitted(): Promise<void> {
  await fetchRequests()
}

async function onReview(id: number, decision: 'ACCEPTED' | 'REJECTED', comment: string): Promise<void> {
  await review(id, decision, comment)
}

async function onWithdraw(id: number): Promise<void> {
  await withdraw(id)
}
</script>

<template>
  <div class="mx-auto max-w-4xl space-y-6 px-4 py-6">
    <!-- ページヘッダー -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-3">
        <NuxtLink
          :to="`/teams/${teamSlug}/shifts`"
          class="text-surface-500 hover:text-surface-700"
        >
          <i class="pi pi-arrow-left" />
        </NuxtLink>
        <PageHeader :title="$t('shift.changeRequest.title')" />
      </div>
    </div>

    <SectionCard v-if="loadError">
      <DashboardErrorState
        :error="loadError"
        testid="change-request-load-error"
        @retry="load"
      />
    </SectionCard>

    <PageLoading v-else-if="loading" />

    <template v-else>
      <!-- 変更依頼作成フォーム。受付外（PUBLISHED 以降）は出さず案内に替える -->
      <ShiftChangeRequestForm
        v-if="canCreate"
        :schedule-id="scheduleId"
        @submitted="onSubmitted"
      />
      <p
        v-else
        data-testid="change-request-closed-notice"
        class="rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-sm text-surface-600"
      >
        {{ $t('shift.changeRequest.closedForNew') }}
      </p>

      <!-- 変更依頼一覧 -->
      <SectionCard>
        <ShiftChangeRequestList
          :requests="requests"
          :current-user-id="authStore.currentUser?.id ?? null"
          :can-review="isAdminOrDeputy"
          :is-loading="isLoading"
          :processing-ids="processingIds"
          @review="onReview"
          @withdraw="onWithdraw"
        />
      </SectionCard>
    </template>
  </div>
</template>
