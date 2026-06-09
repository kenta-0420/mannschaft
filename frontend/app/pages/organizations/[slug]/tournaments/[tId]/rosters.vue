<script setup lang="ts">
import type { OrganizerRosterView } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const tournamentId = Number(route.params.tId)

// 試合ID（クエリパラメータから取得）
const selectedMatchId = ref<number | null>(null)
const matchIdInput = ref<string>('')

// 提出状況・メンバー表
const rosters = ref<OrganizerRosterView[]>([])
const loadingRosters = ref(false)

// 締切設定
const deadlineValue = ref<string>('')
const updatingDeadline = ref(false)
const showDeadlineDialog = ref(false)

// 選択行の展開
const expandedTeamId = ref<number | null>(null)

// 権限
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

// ===== 試合選択 =====

onMounted(async () => {
  await loadPermissions()
  const qMatch = route.query.matchId
  if (qMatch) {
    selectedMatchId.value = Number(qMatch)
    matchIdInput.value = String(qMatch)
    await loadAllRosters(Number(qMatch))
  }
})

async function selectMatch() {
  const matchId = Number(matchIdInput.value)
  if (!matchId || isNaN(matchId)) return
  selectedMatchId.value = matchId
  await loadAllRosters(matchId)
}

// ===== 全チームメンバー表取得 =====

async function loadAllRosters(matchId: number) {
  loadingRosters.value = true
  const composable = useMatchRoster(tournamentId, matchId)
  try {
    const data = await composable.getAllRosters()
    rosters.value = data
  } catch {
    // エラーはcomposable内で通知済み
  } finally {
    loadingRosters.value = false
  }
}

// ===== 提出締切設定 =====

async function updateDeadline() {
  if (!selectedMatchId.value) return
  const composable = useMatchRoster(tournamentId, selectedMatchId.value)
  updatingDeadline.value = true
  try {
    const deadline = deadlineValue.value ? deadlineValue.value : null
    await composable.updateDeadline(deadline)
    showDeadlineDialog.value = false
    // 再取得
    await loadAllRosters(selectedMatchId.value)
  } catch {
    // エラーはcomposable内で通知済み
  } finally {
    updatingDeadline.value = false
  }
}

// ===== 提出率 =====

const submittedCount = computed(() => rosters.value.filter(r => r.submitted).length)
const totalCount = computed(() => rosters.value.length)
const submissionRate = computed(() => {
  if (totalCount.value === 0) return 0
  return Math.round((submittedCount.value / totalCount.value) * 100)
})

function toggleExpand(teamId: number) {
  expandedTeamId.value = expandedTeamId.value === teamId ? null : teamId
}
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-4 flex items-center gap-3">
      <BackButton
        :to="`/organizations/${orgSlug}/tournaments/${tournamentId}`"
        :label="$t('tournament.roster.title')"
      />
      <PageHeader :title="$t('tournament.roster.title')" />
    </div>

    <!-- 試合ID入力 -->
    <SectionCard class="mb-4">
      <div class="flex items-end gap-3">
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium text-surface-600">
            {{ $t('tournament.roster.select_match') }}
          </label>
          <InputText
            v-model="matchIdInput"
            type="number"
            class="w-full"
            @keyup.enter="selectMatch"
          />
        </div>
        <Button
          :label="$t('common.load')"
          icon="pi pi-search"
          :disabled="!matchIdInput"
          @click="selectMatch"
        />
        <Button
          v-if="selectedMatchId && isAdminOrDeputy"
          :label="$t('tournament.roster.deadline_set')"
          icon="pi pi-clock"
          outlined
          @click="showDeadlineDialog = true"
        />
      </div>
    </SectionCard>

    <!-- 試合未選択 -->
    <DashboardEmptyState
      v-if="!selectedMatchId"
      icon="pi pi-list"
      :message="$t('tournament.roster.select_match')"
    />

    <template v-else>
      <PageLoading v-if="loadingRosters" size="40px" />

      <template v-else>
        <!-- 提出率サマリー -->
        <SectionCard v-if="rosters.length > 0" class="mb-4">
          <div class="flex items-center justify-between gap-4">
            <div class="text-sm text-surface-500">
              {{ $t('tournament.roster.submitted') }}:
              <span class="font-bold text-surface-800">{{ submittedCount }} / {{ totalCount }}</span>
              チーム
            </div>
            <div class="h-2 flex-1 overflow-hidden rounded-full bg-surface-200">
              <div
                class="h-full rounded-full bg-primary transition-all duration-500"
                :style="{ width: `${submissionRate}%` }"
              />
            </div>
            <span class="text-sm font-bold text-primary">{{ submissionRate }}%</span>
          </div>
        </SectionCard>

        <!-- チーム別提出状況 -->
        <DashboardEmptyState
          v-if="rosters.length === 0"
          icon="pi pi-users"
          :message="$t('tournament.roster.no_rosters')"
        />

        <div v-else class="flex flex-col gap-3">
          <SectionCard
            v-for="r in rosters"
            :key="r.participantId"
            class="overflow-hidden"
          >
            <!-- チームヘッダー -->
            <div
              class="flex cursor-pointer items-center justify-between gap-3"
              @click="toggleExpand(r.teamId)"
            >
              <div class="flex items-center gap-2">
                <span class="font-semibold">{{ r.teamDisplayName }}</span>
                <span
                  :class="r.submitted ? 'bg-green-100 text-green-700' : 'bg-surface-100 text-surface-500'"
                  class="rounded px-2 py-0.5 text-xs font-medium"
                >
                  {{ r.submitted ? $t('tournament.roster.submitted') : $t('tournament.roster.not_submitted') }}
                </span>
              </div>
              <div class="flex items-center gap-3 text-sm text-surface-400">
                <span>{{ $t('tournament.roster.player_section') }}: {{ r.playerCount }}</span>
                <span>{{ $t('tournament.roster.staff_section') }}: {{ r.staffCount }}</span>
                <i
                  class="pi"
                  :class="expandedTeamId === r.teamId ? 'pi-chevron-up' : 'pi-chevron-down'"
                />
              </div>
            </div>

            <!-- 展開: メンバー詳細 -->
            <template v-if="expandedTeamId === r.teamId && r.submitted">
              <!-- 選手一覧 -->
              <div v-if="r.players.length > 0" class="mt-4">
                <h4 class="mb-2 text-xs font-semibold uppercase text-surface-400">
                  {{ $t('tournament.roster.player_section') }}
                </h4>
                <DataTable :value="r.players" class="text-xs" striped-rows>
                  <Column :header="$t('tournament.roster.jersey_number')" style="width: 5rem">
                    <template #body="{ data }">{{ data.jerseyNumber ?? '-' }}</template>
                  </Column>
                  <Column :header="$t('tournament.roster.registration_number')" style="width: 7rem">
                    <template #body="{ data }">{{ data.registrationNumber ?? '-' }}</template>
                  </Column>
                  <Column :header="$t('tournament.roster.player_name')">
                    <template #body="{ data }">{{ data.displayName }}</template>
                  </Column>
                  <Column :header="$t('tournament.roster.position')" style="width: 6rem">
                    <template #body="{ data }">{{ data.position ?? '-' }}</template>
                  </Column>
                  <Column :header="$t('tournament.roster.starter')" style="width: 5rem">
                    <template #body="{ data }">
                      <i
                        class="pi"
                        :class="data.isStarter ? 'pi-check text-primary' : 'pi-minus text-surface-400'"
                      />
                    </template>
                  </Column>
                </DataTable>
              </div>

              <!-- 役員一覧 -->
              <div v-if="r.staff.length > 0" class="mt-4">
                <h4 class="mb-2 text-xs font-semibold uppercase text-surface-400">
                  {{ $t('tournament.roster.staff_section') }}
                </h4>
                <DataTable :value="r.staff" class="text-xs" striped-rows>
                  <Column :header="$t('tournament.roster.staff_role')" style="width: 8rem">
                    <template #body="{ data }">{{ data.role }}</template>
                  </Column>
                  <Column :header="$t('tournament.roster.staff_name')">
                    <template #body="{ data }">{{ data.name }}</template>
                  </Column>
                </DataTable>
              </div>
            </template>
          </SectionCard>
        </div>
      </template>
    </template>

    <!-- 提出締切設定ダイアログ -->
    <Dialog
      v-model:visible="showDeadlineDialog"
      :header="$t('tournament.roster.deadline_set')"
      modal
      :style="{ width: '24rem' }"
    >
      <div class="flex flex-col gap-3">
        <label class="text-sm text-surface-600">
          {{ $t('tournament.roster.deadline') }}
        </label>
        <InputText
          v-model="deadlineValue"
          type="datetime-local"
          class="w-full"
        />
        <p class="text-xs text-surface-400">
          {{ $t('tournament.roster.deadline_none') }}
        </p>
      </div>
      <template #footer>
        <Button :label="$t('common.cancel')" text @click="showDeadlineDialog = false" />
        <Button
          :label="$t('common.save')"
          :loading="updatingDeadline"
          @click="updateDeadline"
        />
      </template>
    </Dialog>
  </div>
</template>
