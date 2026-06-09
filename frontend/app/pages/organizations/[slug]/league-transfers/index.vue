<script setup lang="ts">
import type { components } from '~/types/generated/index'
import { useLeagueTransfer } from '~/composables/tournament/useLeagueTransfer'
import { useTournamentBase } from '~/composables/tournament/useTournamentBase'

definePageMeta({ layout: 'organization', middleware: 'auth' })

type LeagueTransferResponse = components['schemas']['LeagueTransferResponse']
type TransferCandidateResponse = components['schemas']['TransferCandidateResponse']
type TournamentResponse = import('~/types/tournament').TournamentResponse
type TournamentDivision = import('~/types/tournament').TournamentDivision

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const notification = useNotification()

const leagueTransfer = useLeagueTransfer(orgSlug)
const { getTournaments, getDivisions } = useTournamentBase()

// ===== 送り出しセクション =====
const tournaments = ref<TournamentResponse[]>([])
const selectedTournamentId = ref<number | null>(null)
const candidates = ref<TransferCandidateResponse[]>([])
const candidatesLoading = ref(false)
const selectedCandidateIds = ref<number[]>([])
const divisions = ref<TournamentDivision[]>([])

// 昇格ダイアログ用
const showPromoteDialog = ref(false)
const promoteTargetOrgId = ref<number | null>(null)
const promoteMessage = ref('')
const promoteSaving = ref(false)

// 降格ダイアログ用
const showRelegateDialog = ref(false)
const relegateMessage = ref('')
const relegationSaving = ref(false)

// ===== 受信箱セクション =====
const inboundTransfers = ref<LeagueTransferResponse[]>([])
const inboundLoading = ref(false)

// 承認ダイアログ用
const showApproveDialog = ref(false)
const approveTarget = ref<LeagueTransferResponse | null>(null)
const approveTournamentId = ref<number | null>(null)
const approveDivisionId = ref<number | null>(null)
const approveDivisions = ref<TournamentDivision[]>([])
const approveSaving = ref(false)

// アクティブタブ
const activeTab = ref<'dispatch' | 'inbound'>('dispatch')

// --- 大会一覧ロード ---
async function loadTournaments() {
  try {
    const res = await getTournaments(orgSlug, { status: 'COMPLETED' })
    tournaments.value = res.data ?? []
  } catch {
    notification.error(t('transfer.select_tournament'))
  }
}

// --- 候補チーム取得 ---
async function loadCandidates() {
  if (!selectedTournamentId.value) {
    candidates.value = []
    return
  }
  candidatesLoading.value = true
  selectedCandidateIds.value = []
  try {
    const res = await leagueTransfer.getCandidates(selectedTournamentId.value)
    candidates.value = res.data ?? []
    // ディビジョン一覧も取得（承認時に使用）
    const divRes = await getDivisions(orgSlug, selectedTournamentId.value)
    divisions.value = divRes.data ?? []
  } catch {
    notification.error(t('transfer.empty_candidates'))
  } finally {
    candidatesLoading.value = false
  }
}

// --- 受信箱取得 ---
async function loadInbound() {
  inboundLoading.value = true
  try {
    const res = await leagueTransfer.getInboundTransfers()
    inboundTransfers.value = res.data ?? []
  } catch {
    notification.error(t('transfer.empty_inbound'))
  } finally {
    inboundLoading.value = false
  }
}

// --- 昇格送り出し ---
function openPromoteDialog() {
  promoteTargetOrgId.value = null
  promoteMessage.value = ''
  showPromoteDialog.value = true
}

async function doPromote() {
  if (!selectedTournamentId.value || selectedCandidateIds.value.length === 0) return
  promoteSaving.value = true
  try {
    await leagueTransfer.promote(selectedTournamentId.value, {
      teamIds: selectedCandidateIds.value,
      targetOrganizationId: promoteTargetOrgId.value ?? undefined,
      message: promoteMessage.value || undefined,
    })
    notification.success(t('transfer.promote'))
    showPromoteDialog.value = false
    selectedCandidateIds.value = []
    await loadCandidates()
    await loadInbound()
  } catch {
    notification.error(t('transfer.promote'))
  } finally {
    promoteSaving.value = false
  }
}

// --- 降格送り出し ---
function openRelegateDialog() {
  relegateMessage.value = ''
  showRelegateDialog.value = true
}

async function doRelegate() {
  if (!selectedTournamentId.value || selectedCandidateIds.value.length === 0) return
  relegationSaving.value = true
  try {
    await leagueTransfer.relegate(selectedTournamentId.value, {
      teamIds: selectedCandidateIds.value,
      message: relegateMessage.value || undefined,
    })
    notification.success(t('transfer.relegate'))
    showRelegateDialog.value = false
    selectedCandidateIds.value = []
    await loadCandidates()
    await loadInbound()
  } catch {
    notification.error(t('transfer.relegate'))
  } finally {
    relegationSaving.value = false
  }
}

// --- 承認 ---
async function openApproveDialog(transfer: LeagueTransferResponse) {
  approveTarget.value = transfer
  approveTournamentId.value = null
  approveDivisionId.value = null
  approveDivisions.value = []
  showApproveDialog.value = true
}

async function onApproveTournamentChange() {
  if (!approveTournamentId.value) {
    approveDivisions.value = []
    return
  }
  try {
    const res = await getDivisions(orgSlug, approveTournamentId.value)
    approveDivisions.value = res.data ?? []
  } catch {
    approveDivisions.value = []
  }
}

async function doApprove() {
  if (!approveTarget.value?.id || !approveTournamentId.value || !approveDivisionId.value) return
  approveSaving.value = true
  try {
    await leagueTransfer.approve(
      approveTournamentId.value,
      approveDivisionId.value,
      approveTarget.value.id,
    )
    notification.success(t('transfer.approve'))
    showApproveDialog.value = false
    await loadInbound()
  } catch {
    notification.error(t('transfer.approve'))
  } finally {
    approveSaving.value = false
  }
}

// --- 却下 ---
async function doDecline(transfer: LeagueTransferResponse) {
  if (!transfer.id) return
  try {
    await leagueTransfer.decline(transfer.id)
    notification.success(t('transfer.decline'))
    await loadInbound()
  } catch {
    notification.error(t('transfer.decline'))
  }
}

// --- 取消 ---
async function doCancel(transfer: LeagueTransferResponse) {
  if (!transfer.id) return
  try {
    await leagueTransfer.cancel(transfer.id)
    notification.success(t('transfer.cancel'))
    await loadInbound()
  } catch {
    notification.error(t('transfer.cancel'))
  }
}

// --- ステータスラベル ---
function statusLabel(status?: string): string {
  switch (status) {
    case 'DISPATCHED': return t('transfer.status_dispatched')
    case 'PLACED': return t('transfer.status_placed')
    case 'DECLINED': return t('transfer.status_declined')
    case 'CANCELLED': return t('transfer.status_cancelled')
    default: return status ?? '-'
  }
}

function statusClass(status?: string): string {
  switch (status) {
    case 'DISPATCHED': return 'bg-blue-100 text-blue-700'
    case 'PLACED': return 'bg-green-100 text-green-700'
    case 'DECLINED': return 'bg-red-100 text-red-700'
    case 'CANCELLED': return 'bg-surface-100 text-surface-500'
    default: return 'bg-surface-100'
  }
}

function directionLabel(direction?: string): string {
  if (direction === 'PROMOTION') return t('transfer.direction_promotion')
  if (direction === 'RELEGATION') return t('transfer.direction_relegation')
  return direction ?? '-'
}

// --- 選択チェックボックス ---
function toggleCandidate(teamId: number | undefined) {
  if (teamId === undefined) return
  const idx = selectedCandidateIds.value.indexOf(teamId)
  if (idx >= 0) {
    selectedCandidateIds.value.splice(idx, 1)
  } else {
    selectedCandidateIds.value.push(teamId)
  }
}

function isCandidateSelected(teamId: number | undefined): boolean {
  return teamId !== undefined && selectedCandidateIds.value.includes(teamId)
}

// 昇格候補と降格候補を分類
const promotionCandidates = computed(() =>
  candidates.value.filter(c => c.direction === 'PROMOTION'),
)
const relegationCandidates = computed(() =>
  candidates.value.filter(c => c.direction === 'RELEGATION'),
)

// 受信箱のうち DISPATCHED のみ操作可能
const pendingInbound = computed(() =>
  inboundTransfers.value.filter(t => t.status === 'DISPATCHED'),
)
const historyInbound = computed(() =>
  inboundTransfers.value.filter(t => t.status !== 'DISPATCHED'),
)

// 初期ロード
onMounted(async () => {
  await Promise.all([loadTournaments(), loadInbound()])
})

watch(selectedTournamentId, loadCandidates)
</script>

<template>
  <div class="p-4 max-w-5xl mx-auto">
    <h1 class="text-2xl font-bold mb-6">
      {{ $t('transfer.title') }}
    </h1>

    <!-- タブ切り替え -->
    <div class="flex gap-2 mb-6 border-b border-surface-200">
      <button
        class="px-4 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'dispatch' ? 'border-b-2 border-primary-500 text-primary-600' : 'text-surface-600 hover:text-surface-900'"
        @click="activeTab = 'dispatch'"
      >
        {{ $t('transfer.dispatch') }}
      </button>
      <button
        class="px-4 py-2 text-sm font-medium transition-colors"
        :class="activeTab === 'inbound' ? 'border-b-2 border-primary-500 text-primary-600' : 'text-surface-600 hover:text-surface-900'"
        @click="activeTab = 'inbound'"
      >
        {{ $t('transfer.inbound') }}
        <span
          v-if="pendingInbound.length > 0"
          class="ml-1 bg-red-500 text-white text-xs px-1.5 py-0.5 rounded-full"
        >{{ pendingInbound.length }}</span>
      </button>
    </div>

    <!-- ===== 送り出しセクション ===== -->
    <div v-if="activeTab === 'dispatch'">
      <!-- 大会選択 -->
      <div class="mb-4">
        <label class="block text-sm font-medium text-surface-700 mb-1">{{ $t('transfer.select_tournament') }}</label>
        <Select
          v-model="selectedTournamentId"
          :options="tournaments"
          option-label="title"
          option-value="id"
          :placeholder="$t('transfer.select_tournament')"
          class="w-full max-w-sm"
        />
      </div>

      <div v-if="candidatesLoading" class="text-center py-8 text-surface-500">
        <i class="pi pi-spin pi-spinner text-xl" />
      </div>

      <template v-else-if="selectedTournamentId">
        <!-- 昇格候補 -->
        <div class="mb-6">
          <h2 class="text-lg font-semibold mb-3">
            {{ $t('transfer.direction_promotion') }} — {{ $t('transfer.candidates') }}
          </h2>
          <div v-if="promotionCandidates.length === 0" class="text-surface-400 text-sm py-4">
            {{ $t('transfer.empty_candidates') }}
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="c in promotionCandidates"
              :key="c.teamId"
              class="flex items-center gap-3 p-3 border border-surface-200 rounded-lg hover:bg-surface-50"
            >
              <Checkbox
                :model-value="isCandidateSelected(c.teamId)"
                binary
                @change="toggleCandidate(c.teamId)"
              />
              <div class="flex-1">
                <span class="font-medium">{{ $t('transfer.team') }} #{{ c.teamId }}</span>
                <span class="ml-3 text-sm text-surface-500">{{ c.sourceDivisionName }}</span>
                <span class="ml-3 text-sm text-surface-500">{{ $t('transfer.rank') }}: {{ c.finalRank ?? '-' }}</span>
              </div>
            </div>
          </div>
          <Button
            v-if="promotionCandidates.length > 0"
            class="mt-3"
            :label="$t('transfer.promote')"
            icon="pi pi-arrow-up"
            severity="success"
            :disabled="selectedCandidateIds.length === 0"
            @click="openPromoteDialog"
          />
        </div>

        <!-- 降格候補 -->
        <div>
          <h2 class="text-lg font-semibold mb-3">
            {{ $t('transfer.direction_relegation') }} — {{ $t('transfer.candidates') }}
          </h2>
          <div v-if="relegationCandidates.length === 0" class="text-surface-400 text-sm py-4">
            {{ $t('transfer.empty_candidates') }}
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="c in relegationCandidates"
              :key="c.teamId"
              class="flex items-center gap-3 p-3 border border-surface-200 rounded-lg hover:bg-surface-50"
            >
              <Checkbox
                :model-value="isCandidateSelected(c.teamId)"
                binary
                @change="toggleCandidate(c.teamId)"
              />
              <div class="flex-1">
                <span class="font-medium">{{ $t('transfer.team') }} #{{ c.teamId }}</span>
                <span class="ml-3 text-sm text-surface-500">{{ c.sourceDivisionName }}</span>
                <span class="ml-3 text-sm text-surface-500">{{ $t('transfer.rank') }}: {{ c.finalRank ?? '-' }}</span>
              </div>
            </div>
          </div>
          <Button
            v-if="relegationCandidates.length > 0"
            class="mt-3"
            :label="$t('transfer.relegate')"
            icon="pi pi-arrow-down"
            severity="warning"
            :disabled="selectedCandidateIds.length === 0"
            @click="openRelegateDialog"
          />
        </div>
      </template>

      <div v-else class="text-surface-400 text-sm py-8 text-center">
        {{ $t('transfer.select_tournament') }}
      </div>
    </div>

    <!-- ===== 受信箱セクション ===== -->
    <div v-if="activeTab === 'inbound'">
      <div v-if="inboundLoading" class="text-center py-8 text-surface-500">
        <i class="pi pi-spin pi-spinner text-xl" />
      </div>

      <template v-else>
        <!-- 審査中 -->
        <h2 class="text-lg font-semibold mb-3">
          {{ $t('transfer.status_dispatched') }}
        </h2>
        <div v-if="pendingInbound.length === 0" class="text-surface-400 text-sm py-4 mb-6">
          {{ $t('transfer.empty_inbound') }}
        </div>
        <div v-else class="space-y-3 mb-8">
          <div
            v-for="tr in pendingInbound"
            :key="tr.id"
            class="p-4 border border-surface-200 rounded-lg"
          >
            <div class="flex items-start justify-between gap-4">
              <div class="space-y-1">
                <div class="flex items-center gap-2">
                  <span
                    class="px-2 py-0.5 rounded text-xs font-medium"
                    :class="statusClass(tr.status)"
                  >{{ statusLabel(tr.status) }}</span>
                  <span class="text-sm font-medium">{{ directionLabel(tr.direction) }}</span>
                </div>
                <div class="text-sm text-surface-600">
                  {{ $t('transfer.team') }} #{{ tr.teamId }}
                  <span v-if="tr.season"> — {{ $t('transfer.season') }}: {{ tr.season }}</span>
                  <span v-if="tr.finalRank"> — {{ $t('transfer.rank') }}: {{ tr.finalRank }}</span>
                </div>
                <div v-if="tr.message" class="text-sm text-surface-500 italic">{{ tr.message }}</div>
              </div>
              <div class="flex gap-2 shrink-0">
                <Button
                  :label="$t('transfer.approve')"
                  icon="pi pi-check"
                  size="small"
                  @click="openApproveDialog(tr)"
                />
                <Button
                  :label="$t('transfer.decline')"
                  icon="pi pi-times"
                  severity="danger"
                  outlined
                  size="small"
                  @click="doDecline(tr)"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- 履歴 -->
        <h2 class="text-lg font-semibold mb-3">
          {{ $t('transfer.history_title') }}
        </h2>
        <div v-if="historyInbound.length === 0" class="text-surface-400 text-sm py-4">
          {{ $t('transfer.empty_history') }}
        </div>
        <div v-else class="space-y-2">
          <div
            v-for="tr in historyInbound"
            :key="tr.id"
            class="flex items-center gap-3 p-3 border border-surface-100 rounded-lg"
          >
            <span
              class="px-2 py-0.5 rounded text-xs font-medium"
              :class="statusClass(tr.status)"
            >{{ statusLabel(tr.status) }}</span>
            <span class="text-sm">{{ directionLabel(tr.direction) }}</span>
            <span class="text-sm text-surface-600">{{ $t('transfer.team') }} #{{ tr.teamId }}</span>
            <span v-if="tr.season" class="text-sm text-surface-400">{{ tr.season }}</span>
            <Button
              v-if="tr.status === 'DISPATCHED'"
              :label="$t('transfer.cancel')"
              icon="pi pi-ban"
              severity="secondary"
              outlined
              size="small"
              class="ml-auto"
              @click="doCancel(tr)"
            />
          </div>
        </div>
      </template>
    </div>

    <!-- ===== 昇格確認ダイアログ ===== -->
    <Dialog
      v-model:visible="showPromoteDialog"
      modal
      :header="$t('transfer.promote')"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <p class="text-sm text-surface-700">{{ $t('transfer.confirm_promote') }}</p>
        <div>
          <label class="block text-sm font-medium text-surface-700 mb-1">
            {{ $t('transfer.target_division') }} (送り先組織ID)
          </label>
          <InputNumber
            v-model="promoteTargetOrgId"
            :placeholder="$t('transfer.target_division')"
            class="w-full"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-surface-700 mb-1">メッセージ（任意）</label>
          <Textarea
            v-model="promoteMessage"
            rows="2"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('common.cancel')" severity="secondary" outlined @click="showPromoteDialog = false" />
        <Button
          :label="$t('transfer.promote')"
          icon="pi pi-arrow-up"
          severity="success"
          :loading="promoteSaving"
          @click="doPromote"
        />
      </template>
    </Dialog>

    <!-- ===== 降格確認ダイアログ ===== -->
    <Dialog
      v-model:visible="showRelegateDialog"
      modal
      :header="$t('transfer.relegate')"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <p class="text-sm text-surface-700">{{ $t('transfer.confirm_relegate') }}</p>
        <div>
          <label class="block text-sm font-medium text-surface-700 mb-1">メッセージ（任意）</label>
          <Textarea
            v-model="relegateMessage"
            rows="2"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('common.cancel')" severity="secondary" outlined @click="showRelegateDialog = false" />
        <Button
          :label="$t('transfer.relegate')"
          icon="pi pi-arrow-down"
          severity="warning"
          :loading="relegationSaving"
          @click="doRelegate"
        />
      </template>
    </Dialog>

    <!-- ===== 承認ダイアログ ===== -->
    <Dialog
      v-model:visible="showApproveDialog"
      modal
      :header="$t('transfer.approve')"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <p class="text-sm text-surface-700">{{ $t('transfer.confirm_approve') }}</p>
        <div>
          <label class="block text-sm font-medium text-surface-700 mb-1">{{ $t('transfer.select_tournament') }}</label>
          <Select
            v-model="approveTournamentId"
            :options="tournaments"
            option-label="title"
            option-value="id"
            :placeholder="$t('transfer.select_tournament')"
            class="w-full"
            @change="onApproveTournamentChange"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-surface-700 mb-1">{{ $t('transfer.target_division') }}</label>
          <Select
            v-model="approveDivisionId"
            :options="approveDivisions"
            option-label="name"
            option-value="id"
            :placeholder="$t('transfer.target_division')"
            :disabled="approveDivisions.length === 0"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('common.cancel')" severity="secondary" outlined @click="showApproveDialog = false" />
        <Button
          :label="$t('transfer.approve')"
          icon="pi pi-check"
          :loading="approveSaving"
          :disabled="!approveTournamentId || !approveDivisionId"
          @click="doApprove"
        />
      </template>
    </Dialog>
  </div>
</template>
