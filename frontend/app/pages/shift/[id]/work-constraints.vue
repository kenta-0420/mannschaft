<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
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
const { getAllMembers } = useTeamApi()
const { warn } = useNotification()

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

/**
 * チームデフォルト未設定を 404 として検知するヘルパー。
 * 403/500/ネットワーク断は握りつぶさず上位へ再 throw させるため、404 のみ許容する。
 */
function isNotFoundError(error: unknown): boolean {
  const e = error as {
    statusCode?: number
    response?: { status?: number }
    data?: { error?: { code?: string } }
  }
  if (e?.statusCode === 404) return true
  if (e?.response?.status === 404) return true
  if (e?.data?.error?.code === 'WORK_CONSTRAINT_NOT_FOUND') return true
  return false
}

/** 取得失敗（空状態へ倒さず DashboardErrorState で出す。AC-E1〜E3） */
const loadError = ref<unknown>(null)

// メンバー名の解決（AC-N3）。取得失敗は制約の表示を止めず、名前だけ「不明なユーザー」に倒す。
const members = ref<MemberResponse[]>([])
const membersError = ref<unknown>(null)
const membersLoading = ref(false)

const teamSlug = computed(() =>
  schedule.value
    ? teamStore.myTeams.find((m) => m.id === schedule.value!.teamId)?.slug ?? null
    : null,
)

async function loadMembers(): Promise<void> {
  const slug = teamSlug.value
  if (!slug) return
  membersLoading.value = true
  membersError.value = null
  try {
    // 件数上限のあるページング API ではなく全件取得 API を使う（AC-N5: 全員に到達できる）
    members.value = (await getAllMembers(slug)).data
  } catch (error) {
    members.value = []
    membersError.value = error
  } finally {
    membersLoading.value = false
  }
}

const memberNameById = computed(() => new Map(members.value.map((m) => [m.userId, m.displayName])))

function memberName(userId: number | null): string {
  return (userId !== null && memberNameById.value.get(userId)) || t('shift.workConstraint.unknownMember')
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    schedule.value = await getSchedule(scheduleId.value)
    if (!canManage.value) return
    const teamId = String(schedule.value.teamId)
    const [constraints, def] = await Promise.all([
      listConstraints(teamId),
      (async () => {
        try {
          return await getTeamDefault(teamId)
        } catch (e) {
          // 未設定（404 / WORK_CONSTRAINT_NOT_FOUND）のみ null として許容。
          // 403/500/ネットワーク断などは上位へ伝搬させ、エラー状態で出す。
          if (isNotFoundError(e)) return null
          throw e
        }
      })(),
      loadMembers(),
    ])
    teamDefault.value = def
    // userId !== null のもののみメンバー個別制約として表示
    memberConstraints.value = constraints.filter((c) => c.userId !== null)
  } catch (error) {
    schedule.value = null
    teamDefault.value = null
    memberConstraints.value = []
    loadError.value = error
  } finally {
    loading.value = false
  }
}

// このページの一覧取得 API（listConstraints）は BE 側で ADMIN/DEPUTY_ADMIN 限定。
// 一般メンバーの直リンク・ブックマーク到達を塞ぐため、権限確定時点で詳細ページへ戻す。
watch(
  [schedule, canManage],
  () => {
    if (schedule.value && !canManage.value) {
      warn(t('shift.detail.accessDeniedRedirect'))
      navigateTo(`/shift/${scheduleId.value}`)
    }
  },
  { immediate: true },
)

onMounted(async () => {
  await teamStore.fetchMyTeams()
  await load()
})

// =====================================================
// メンバー追加フォーム（チームのメンバー一覧から名前で選ぶ）
// =====================================================
const showAddMemberDialog = ref(false)
const addMemberUserId = ref<number | null>(null)
const memberSearch = ref('')

/** 個別制約が既にあるメンバーは出さない（重複追加できない。AC-N2）。 */
const selectableMembers = computed(() => {
  const q = memberSearch.value.trim().toLowerCase()
  const existing = new Set(memberConstraints.value.map((c) => c.userId))
  return members.value.filter(
    (m) => !existing.has(m.userId) && (q === '' || m.displayName.toLowerCase().includes(q)),
  )
})

function openAddMember() {
  addMemberUserId.value = null
  memberSearch.value = ''
  showAddMemberDialog.value = true
  if (members.value.length === 0 && !membersLoading.value) void loadMembers()
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
        {{ schedule?.content.title ?? '...' }}
      </h1>
      <ShiftStatusBadge v-if="schedule" :status="schedule.status.status" />
    </div>

    <DashboardErrorState
      v-if="loadError"
      :error="loadError"
      testid="shift-load-error"
      @retry="load"
    />

    <PageLoading v-else-if="loading" />

    <template v-else>
      <!-- タブ -->
      <ShiftDetailTabs :schedule-id="scheduleId" :team-id="schedule?.teamId ?? null" current="constraints" />

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
          :team-id="String(schedule.teamId)"
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
            data-testid="work-constraint-add-member"
            class="min-h-[44px]"
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
            :data-testid="`work-constraint-card-${constraint.userId}`"
            class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-900"
          >
            <!-- アコーディオンヘッダー -->
            <button
              class="flex min-h-[44px] w-full items-center justify-between px-4 py-3 text-left"
              @click="toggleExpand(constraint.userId!)"
            >
              <span class="text-sm font-medium">
                {{ memberName(constraint.userId) }}
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
                :team-id="String(schedule.teamId)"
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
      <div class="flex flex-col gap-3">
        <DashboardErrorState
          v-if="membersError"
          :error="membersError"
          :message="t('shift.workConstraint.membersLoadError')"
          testid="work-constraint-members-error"
          @retry="loadMembers"
        />
        <template v-else>
          <InputText
            v-model="memberSearch"
            data-testid="work-constraint-member-search"
            class="w-full"
            :placeholder="t('shift.workConstraint.searchMember')"
            :aria-label="t('shift.workConstraint.searchMember')"
          />
          <PageLoading v-if="membersLoading" size="32px" />
          <p
            v-else-if="selectableMembers.length === 0"
            class="py-4 text-center text-sm text-surface-500"
          >
            {{ t('shift.workConstraint.noSelectableMembers') }}
          </p>
          <ul v-else class="max-h-64 space-y-1 overflow-y-auto" role="listbox">
            <li v-for="m in selectableMembers" :key="m.userId" role="option" :aria-selected="addMemberUserId === m.userId">
              <button
                type="button"
                :data-testid="`work-constraint-member-option-${m.userId}`"
                class="flex min-h-[44px] w-full items-center rounded-lg border px-3 text-left text-sm"
                :class="
                  addMemberUserId === m.userId
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-surface-200 dark:border-surface-700'
                "
                @click="addMemberUserId = m.userId"
              >
                {{ m.displayName }}
              </button>
            </li>
          </ul>
        </template>
      </div>
      <template #footer>
        <Button :label="t('common.cancel')" text @click="showAddMemberDialog = false" />
        <Button
          data-testid="work-constraint-add-confirm"
          :label="t('shift.workConstraint.addMember')"
          icon="pi pi-check"
          :disabled="!addMemberUserId"
          @click="confirmAddMember"
        />
      </template>
    </Dialog>
  </div>
</template>
