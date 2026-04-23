<script setup lang="ts">
import type {
  ShiftScheduleResponse,
  MemberWorkConstraintResponse,
} from '~/types/shift'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamStore = useTeamStore()
const { getSchedule } = useShiftApi()
const { listConstraints, getTeamDefault } = useMemberWorkConstraintApi()
const { handleApiError } = useErrorHandler()

const scheduleId = computed(() => Number(route.params.id))

// =====================================================
// データ取得
// =====================================================
const schedule = ref<ShiftScheduleResponse | null>(null)
const teamDefault = ref<MemberWorkConstraintResponse | null>(null)
const memberConstraints = ref<MemberWorkConstraintResponse[]>([])
const loading = ref(false)

const canManage = computed(() => {
  if (!schedule.value) return false
  return teamStore.myTeams.some(
    (t) =>
      t.id === schedule.value!.teamId &&
      (t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN' || t.role === 'DEPUTY_ADMIN'),
  )
})

async function load() {
  if (!schedule.value) return
  loading.value = true
  try {
    const [constraints, def] = await Promise.all([
      listConstraints(schedule.value.teamId),
      getTeamDefault(schedule.value.teamId).catch(() => null),
    ])
    teamDefault.value = def
    // userId !== null のもののみメンバー個別制約として表示
    memberConstraints.value = constraints.filter((c) => c.userId !== null)
  } catch (error) {
    handleApiError(error)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  try {
    schedule.value = await getSchedule(scheduleId.value)
    await load()
  } catch (error) {
    handleApiError(error)
  }
})

// =====================================================
// メンバー追加フォーム
// =====================================================
const showAddMemberDialog = ref(false)
const addMemberUserId = ref<number | null>(null)

function openAddMember() {
  addMemberUserId.value = null
  showAddMemberDialog.value = true
}

function handleMemberConstraintSaved(saved: MemberWorkConstraintResponse) {
  const idx = memberConstraints.value.findIndex((c) => c.userId === saved.userId)
  if (idx >= 0) {
    memberConstraints.value[idx] = saved
  } else {
    memberConstraints.value.push(saved)
  }
}

function handleMemberConstraintDeleted(userId: number) {
  memberConstraints.value = memberConstraints.value.filter((c) => c.userId !== userId)
}

// 展開中のメンバー ID
const expandedUserId = ref<number | null>(null)

function toggleExpand(userId: number) {
  expandedUserId.value = expandedUserId.value === userId ? null : userId
}

function confirmAddMember() {
  if (!addMemberUserId.value || !schedule.value) return
  const existing = memberConstraints.value.find((c) => c.userId === addMemberUserId.value)
  if (!existing) {
    memberConstraints.value.push({
      id: -1,
      teamId: schedule.value.teamId,
      userId: addMemberUserId.value,
      maxMonthlyHours: null,
      maxMonthlyDays: null,
      maxConsecutiveDays: null,
      maxNightShiftsPerMonth: null,
      minRestHoursBetweenShifts: null,
      note: null,
    })
  }
  expandedUserId.value = addMemberUserId.value
  showAddMemberDialog.value = false
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 py-6">
    <!-- ナビゲーション -->
    <div class="mb-4 flex items-center gap-2">
      <BackButton :to="`/shift/${scheduleId}`" />
      <h1 class="text-xl font-bold text-surface-800 dark:text-surface-100">
        {{ schedule?.title ?? '...' }}
      </h1>
      <ShiftStatusBadge v-if="schedule" :status="schedule.status" />
    </div>

    <!-- タブ -->
    <nav class="mb-6 flex gap-1 overflow-x-auto border-b border-surface-200 dark:border-surface-700">
      <NuxtLink
        :to="`/shift/${scheduleId}`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-calendar" />{{ t('shift.detail.tabOverview') }}
      </NuxtLink>
      <NuxtLink
        :to="`/shift/${scheduleId}/edit`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-pencil" />{{ t('shift.detail.tabEdit') }}
      </NuxtLink>
      <NuxtLink
        :to="`/shift/${scheduleId}/requests`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-list" />{{ t('shift.detail.tabRequests') }}
      </NuxtLink>
      <span class="flex shrink-0 items-center gap-1.5 border-b-2 border-primary px-4 py-2 text-sm font-medium text-primary">
        <i class="pi pi-shield" />{{ t('shift.detail.tabConstraints') }}
      </span>
    </nav>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- チームデフォルト -->
      <section class="mb-8">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-base font-semibold text-surface-700 dark:text-surface-300">
            {{ t('shift.workConstraint.teamDefault') }}
          </h2>
          <Tag :value="t('shift.workConstraint.appliedToAll')" severity="info" />
        </div>
        <p class="mb-4 text-sm text-surface-500">
          {{ t('shift.workConstraint.teamDefaultDescription') }}
        </p>

        <ShiftWorkConstraintForm
          v-if="canManage && schedule"
          :constraint="teamDefault"
          :team-id="schedule.teamId"
          :user-id="null"
          @saved="teamDefault = $event"
          @deleted="teamDefault = null"
        />
        <div v-else-if="teamDefault" class="rounded-xl border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800">
          <dl class="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
            <div v-if="teamDefault.maxMonthlyHours">
              <dt class="text-surface-500">{{ t('shift.workConstraint.maxMonthlyHours') }}</dt>
              <dd class="font-medium">{{ teamDefault.maxMonthlyHours }}h</dd>
            </div>
            <div v-if="teamDefault.maxMonthlyDays">
              <dt class="text-surface-500">{{ t('shift.workConstraint.maxMonthlyDays') }}</dt>
              <dd class="font-medium">{{ teamDefault.maxMonthlyDays }}{{ t('shift.workConstraint.days') }}</dd>
            </div>
            <div v-if="teamDefault.maxConsecutiveDays">
              <dt class="text-surface-500">{{ t('shift.workConstraint.maxConsecutiveDays') }}</dt>
              <dd class="font-medium">{{ teamDefault.maxConsecutiveDays }}{{ t('shift.workConstraint.days') }}</dd>
            </div>
            <div v-if="teamDefault.maxNightShiftsPerMonth !== null">
              <dt class="text-surface-500">{{ t('shift.workConstraint.maxNightShifts') }}</dt>
              <dd class="font-medium">{{ teamDefault.maxNightShiftsPerMonth }}{{ t('shift.workConstraint.times') }}</dd>
            </div>
            <div v-if="teamDefault.minRestHoursBetweenShifts">
              <dt class="text-surface-500">{{ t('shift.workConstraint.minRestHours') }}</dt>
              <dd class="font-medium">{{ teamDefault.minRestHoursBetweenShifts }}h</dd>
            </div>
          </dl>
          <p v-if="teamDefault.note" class="mt-2 text-xs text-surface-500">{{ teamDefault.note }}</p>
        </div>
        <DashboardEmptyState
          v-else
          icon="pi pi-shield"
          :message="t('shift.workConstraint.noDefault')"
        />
      </section>

      <!-- メンバー個別制約 -->
      <section>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-base font-semibold text-surface-700 dark:text-surface-300">
            {{ t('shift.workConstraint.memberOverrides') }}
          </h2>
          <Button
            v-if="canManage"
            icon="pi pi-plus"
            :label="t('shift.workConstraint.addMember')"
            size="small"
            severity="secondary"
            @click="openAddMember"
          />
        </div>
        <p class="mb-4 text-sm text-surface-500">
          {{ t('shift.workConstraint.memberOverridesDescription') }}
        </p>

        <div v-if="memberConstraints.length === 0">
          <DashboardEmptyState icon="pi pi-user" :message="t('shift.workConstraint.noOverrides')" />
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="constraint in memberConstraints"
            :key="constraint.userId ?? 0"
            class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-900"
          >
            <!-- アコーディオンヘッダー -->
            <button
              class="flex w-full items-center justify-between px-4 py-3 text-left"
              @click="toggleExpand(constraint.userId!)"
            >
              <span class="text-sm font-medium">
                {{ t('shift.workConstraint.memberLabel', { userId: constraint.userId }) }}
              </span>
              <i
                class="pi transition-transform"
                :class="expandedUserId === constraint.userId ? 'pi-chevron-up' : 'pi-chevron-down'"
              />
            </button>

            <!-- 展開コンテンツ -->
            <div v-if="expandedUserId === constraint.userId" class="border-t border-surface-100 px-4 pb-4 pt-3 dark:border-surface-700">
              <ShiftWorkConstraintForm
                v-if="canManage && schedule"
                :constraint="constraint"
                :team-id="schedule.teamId"
                :user-id="constraint.userId"
                @saved="handleMemberConstraintSaved"
                @deleted="handleMemberConstraintDeleted(constraint.userId!)"
              />
              <div v-else class="text-sm text-surface-500">
                {{ t('shift.workConstraint.readOnly') }}
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- メンバー追加ダイアログ -->
    <Dialog
      v-model:visible="showAddMemberDialog"
      :header="t('shift.workConstraint.addMember')"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.userId') }}</label>
          <InputNumber
            v-model="addMemberUserId"
            :min="1"
            class="w-full"
            :placeholder="t('shift.workConstraint.userIdPlaceholder')"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="t('common.cancel')" text @click="showAddMemberDialog = false" />
        <Button
          :label="t('common.next')"
          icon="pi pi-check"
          :disabled="!addMemberUserId"
          @click="confirmAddMember"
        />
      </template>
    </Dialog>
  </div>
</template>
