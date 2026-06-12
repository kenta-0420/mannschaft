<script setup lang="ts">
import type {
  MatchRosterResponse,
  RosterPlayerEntry,
  RosterStaffEntry,
  SubmitRosterRequest,
  ApplyRosterTemplateRequest,
  EntryTemplate,
} from '~/types/tournament'

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const tournamentId = Number(route.params.tId)

// 試合選択
const selectedMatchId = ref<number | null>(null)

// 試合一覧はエントリーの参加チームから取得できないため、
// チームが参加している試合の matchId をクエリパラメータから受け取る対応を想定
// （将来的には参加試合一覧 API から取得）
onMounted(() => {
  const qMatch = route.query.matchId
  if (qMatch) {
    selectedMatchId.value = Number(qMatch)
    loadRoster(Number(qMatch))
  }
})

const roster = ref<MatchRosterResponse | null>(null)
const loadingRoster = ref(false)

// エントリーテンプレ一覧
const { getEntryTemplates } = useTournamentApi()
const templates = ref<EntryTemplate[]>([])
const loadingTemplates = ref(false)

// 選手フォーム状態
const playerRows = ref<RosterPlayerEntry[]>([])
const staffRows = ref<RosterStaffEntry[]>([])

// テンプレ選択モーダル
const showTemplateModal = ref(false)
const selectedTemplateId = ref<string | null>(null)
const overwriteExisting = ref(false)

// 権限
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

// ===== メンバー表のロード =====

async function loadRoster(matchId: number) {
  const composable = useMatchRoster(tournamentId, matchId)
  loadingRoster.value = true
  try {
    const data = await composable.getMyRoster()
    roster.value = data
    if (data) {
      playerRows.value = data.players.map(p => ({
        userId: p.userId,
        isStarter: p.isStarter,
        jerseyNumber: p.jerseyNumber,
        position: p.position ?? undefined,
        registrationNumber: p.registrationNumber ?? undefined,
        uniformSetId: p.uniformSetId ?? undefined,
      }))
      staffRows.value = data.staff.map(s => ({
        role: s.role,
        name: s.name,
        userId: s.userId ?? undefined,
      }))
    }
  } catch {
    // エラーはcomposable内で通知済み
  } finally {
    loadingRoster.value = false
  }
}

// ===== テンプレ一覧のロード =====

async function loadTemplates() {
  loadingTemplates.value = true
  try {
    const orgId = '0' // NOTE: チームスコープでは organization_id は不要（BE API は orgId ベースだが team スコープ用 API に変更）
    // チームのエントリーテンプレ一覧を取得
    // useTournamentParticipants.getEntryTemplates は orgId/teamSlug が必要
    // ここでは teamSlug を使用してロード
    const data = await getEntryTemplates(orgId, teamSlug)
    templates.value = Array.isArray(data) ? data : []
  } catch {
    // テンプレ取得失敗はサイレント（テンプレなしで手動入力可）
  } finally {
    loadingTemplates.value = false
  }
}

// ===== テンプレ適用 =====

async function applyTemplate() {
  if (!selectedMatchId.value || !selectedTemplateId.value) return
  const composable = useMatchRoster(tournamentId, selectedMatchId.value)
  const req: ApplyRosterTemplateRequest = {
    templateId: selectedTemplateId.value,
    overwriteExisting: overwriteExisting.value,
  }
  try {
    const data = await composable.applyTemplate(req)
    roster.value = data
    playerRows.value = data.players.map(p => ({
      userId: p.userId,
      isStarter: p.isStarter,
      jerseyNumber: p.jerseyNumber,
      position: p.position ?? undefined,
      registrationNumber: p.registrationNumber ?? undefined,
    }))
    staffRows.value = data.staff.map(s => ({
      role: s.role,
      name: s.name,
      userId: s.userId ?? undefined,
    }))
    showTemplateModal.value = false
  } catch {
    // エラーはcomposable内で通知済み
  }
}

// ===== メンバー表提出 =====

const submitting = ref(false)

async function submitRoster() {
  if (!selectedMatchId.value) return
  const composable = useMatchRoster(tournamentId, selectedMatchId.value)
  submitting.value = true
  const req: SubmitRosterRequest = {
    players: playerRows.value,
    staff: staffRows.value,
  }
  try {
    const data = await composable.submitMyRoster(req)
    roster.value = data
  } catch {
    // エラーはcomposable内で通知済み
  } finally {
    submitting.value = false
  }
}

// ===== 選手行の操作 =====

function addPlayer() {
  playerRows.value.push({ userId: 0, isStarter: null })
}

function removePlayer(index: number) {
  playerRows.value.splice(index, 1)
}

function addStaff() {
  staffRows.value.push({ role: '', name: '' })
}

function removeStaff(index: number) {
  staffRows.value.splice(index, 1)
}

// ===== 締切状態 =====

const isLocked = computed(() => {
  if (!roster.value) return false
  return roster.value.locked
})

const deadlineLabel = computed(() => {
  if (!roster.value?.rosterDeadline) return t('tournament.roster.deadline_none')
  return new Date(roster.value.rosterDeadline).toLocaleString()
})

// ===== 初期化 =====

onMounted(async () => {
  await loadPermissions()
  await loadTemplates()
})
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/teams/${teamSlug}/tournaments`" :label="$t('tournament.roster.title')" />
      <PageHeader :title="$t('tournament.roster.title')" />
    </div>

    <!-- 試合未選択の場合 -->
    <DashboardEmptyState
      v-if="!selectedMatchId"
      icon="pi pi-list"
      :message="$t('tournament.roster.select_match')"
    />

    <template v-else>
      <!-- ロード中 -->
      <PageLoading v-if="loadingRoster" size="40px" />

      <template v-else>
        <!-- 締切情報 -->
        <SectionCard v-if="roster" class="mb-4">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex items-center gap-2 text-sm">
              <span class="text-surface-500">{{ $t('tournament.roster.deadline') }}:</span>
              <span
                :class="isLocked ? 'font-semibold text-red-600' : 'text-surface-700'"
              >
                {{ deadlineLabel }}
              </span>
              <span
                v-if="isLocked"
                class="rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-600"
              >
                {{ $t('tournament.roster.deadline_passed') }}
              </span>
            </div>
            <span
              v-if="roster.locked"
              class="rounded bg-orange-100 px-2 py-0.5 text-xs text-orange-700"
            >
              {{ $t('tournament.roster.locked_message') }}
            </span>
          </div>
        </SectionCard>

        <!-- テンプレ適用ボタン（ADMIN/DEPUTY かつ未ロック） -->
        <div
          v-if="isAdminOrDeputy && !isLocked && templates.length > 0"
          class="mb-4"
        >
          <Button
            :label="$t('tournament.roster.apply_template')"
            icon="pi pi-copy"
            outlined
            @click="showTemplateModal = true"
          />
        </div>

        <!-- 選手セクション -->
        <SectionCard :title="$t('tournament.roster.player_section')" class="mb-4">
          <DataTable :value="playerRows" class="text-sm">
            <Column header="#" style="width: 3rem">
              <template #body="{ index }">
                {{ index + 1 }}
              </template>
            </Column>
            <Column :header="$t('tournament.roster.jersey_number')" style="width: 6rem">
              <template #body="{ data: row }: { data: RosterPlayerEntry }">
                <InputNumber
                  v-model="row.jerseyNumber"
                  :disabled="isLocked || !isAdminOrDeputy"
                  :min="1"
                  :max="999"
                  input-class="w-16 text-center"
                />
              </template>
            </Column>
            <Column :header="$t('tournament.roster.registration_number')" style="width: 8rem">
              <template #body="{ data: row }: { data: RosterPlayerEntry }">
                <InputText
                  v-model="row.registrationNumber"
                  :disabled="isLocked || !isAdminOrDeputy"
                  class="w-full"
                />
              </template>
            </Column>
            <Column :header="$t('tournament.roster.position')" style="width: 8rem">
              <template #body="{ data: row }: { data: RosterPlayerEntry }">
                <InputText
                  v-model="row.position"
                  :disabled="isLocked || !isAdminOrDeputy"
                  class="w-full"
                />
              </template>
            </Column>
            <Column :header="$t('tournament.roster.starter')" style="width: 6rem">
              <template #body="{ data: row }: { data: RosterPlayerEntry }">
                <Checkbox
                  v-model="row.isStarter"
                  :binary="true"
                  :disabled="isLocked || !isAdminOrDeputy"
                />
              </template>
            </Column>
            <Column v-if="isAdminOrDeputy && !isLocked" style="width: 4rem">
              <template #body="{ index }">
                <Button
                  icon="pi pi-trash"
                  text
                  severity="danger"
                  size="small"
                  @click="removePlayer(index)"
                />
              </template>
            </Column>
          </DataTable>

          <Button
            v-if="isAdminOrDeputy && !isLocked"
            :label="$t('tournament.roster.add_player')"
            icon="pi pi-plus"
            text
            class="mt-2"
            @click="addPlayer"
          />
        </SectionCard>

        <!-- ベンチ役員セクション -->
        <SectionCard :title="$t('tournament.roster.staff_section')" class="mb-4">
          <DataTable :value="staffRows" class="text-sm">
            <Column :header="$t('tournament.roster.staff_role')" style="width: 8rem">
              <template #body="{ data: row }: { data: RosterStaffEntry }">
                <InputText
                  v-model="row.role"
                  :disabled="isLocked || !isAdminOrDeputy"
                  class="w-full"
                />
              </template>
            </Column>
            <Column :header="$t('tournament.roster.staff_name')">
              <template #body="{ data: row }: { data: RosterStaffEntry }">
                <InputText
                  v-model="row.name"
                  :disabled="isLocked || !isAdminOrDeputy"
                  class="w-full"
                />
              </template>
            </Column>
            <Column v-if="isAdminOrDeputy && !isLocked" style="width: 4rem">
              <template #body="{ index }">
                <Button
                  icon="pi pi-trash"
                  text
                  severity="danger"
                  size="small"
                  @click="removeStaff(index)"
                />
              </template>
            </Column>
          </DataTable>

          <Button
            v-if="isAdminOrDeputy && !isLocked"
            :label="$t('tournament.roster.add_staff')"
            icon="pi pi-plus"
            text
            class="mt-2"
            @click="addStaff"
          />
        </SectionCard>

        <!-- 提出ボタン -->
        <div v-if="isAdminOrDeputy && !isLocked" class="flex justify-end">
          <Button
            :label="$t('tournament.roster.submit')"
            icon="pi pi-send"
            :loading="submitting"
            @click="submitRoster"
          />
        </div>
      </template>
    </template>

    <!-- テンプレ選択ダイアログ -->
    <Dialog
      v-model:visible="showTemplateModal"
      :header="$t('tournament.roster.select_template')"
      modal
      :style="{ width: '28rem' }"
    >
      <div class="flex flex-col gap-3">
        <div
          v-for="tmpl in templates"
          :key="tmpl.id"
          class="flex cursor-pointer items-center gap-3 rounded-lg border p-3 transition-colors"
          :class="
            selectedTemplateId === tmpl.id
              ? 'border-primary bg-primary-50'
              : 'border-surface-200 hover:border-primary-300'
          "
          @click="selectedTemplateId = tmpl.id"
        >
          <i
            class="pi"
            :class="selectedTemplateId === tmpl.id ? 'pi-check-circle text-primary' : 'pi-circle'"
          />
          <div>
            <p class="text-sm font-medium">{{ tmpl.name }}</p>
            <p class="text-xs text-surface-400">{{ $t('tournament.roster.player_count', { count: tmpl.memberCount }) }}</p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox v-model="overwriteExisting" :binary="true" input-id="overwrite" />
          <label for="overwrite" class="text-sm">{{ $t('tournament.roster.overwrite_existing') }}</label>
        </div>
      </div>

      <template #footer>
        <Button
          :label="$t('common.cancel')"
          text
          @click="showTemplateModal = false"
        />
        <Button
          :label="$t('tournament.roster.apply_template')"
          :disabled="!selectedTemplateId"
          @click="applyTemplate"
        />
      </template>
    </Dialog>
  </div>
</template>
