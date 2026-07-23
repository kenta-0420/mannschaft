<script setup lang="ts">
/**
 * F20.3 Phase3 B-3: シスアド ベータ特典 審査運用画面（設計書 04 §B-3 / 02 §4）。
 *
 * `pages/system-admin/billing.vue`（TabView + DataTable + Dialog CRUD）を金型に踏襲。
 * TabView 3 枚: 付与一覧（フィルタ＋行操作＋手動付与）／付与候補（dry-run・Phase1 は TEAM_ORG のみ）／
 * 条件マスタ（betaPhase × grantKind の GET/PUT）。
 *
 * ガードは金型どおり `middleware: 'auth'` のみ（SYSTEM_ADMIN の FE ミドルウェアは無い）。
 * 非 SYSTEM_ADMIN で API が 403 のときは handleApiError で綺麗に表示する（白画面にしない）。
 */
import type {
  BetaPerkGrantDetail,
  BetaPerkCandidate,
  BetaPerkCriteriaResponse,
  BetaPerkGrantKind,
  BetaPerkScopeKind,
} from '~/composables/useBetaPerksApi'

definePageMeta({ middleware: 'auth' })

const { t, te } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const betaPerksApi = useBetaPerksApi()

const activeTab = ref(0)
const helpVisible = ref(false)

const AK = 'billing.betaPerks.admin'

// 指標キー → 表示名（eligibility.metric.*・未定義キーは素通し）
function metricLabel(key?: string): string {
  if (!key) return ''
  const k = `billing.betaPerks.eligibility.metric.${key}`
  return te(k) ? t(k) : key
}

// ISO 日時 → ローカル日付表示（null は呼び出し側で validUntilNone に振り替える）
function formatDate(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('ja-JP')
}

function grantKindLabel(kind?: string): string {
  if (kind === 'INDIVIDUAL') return t(`${AK}.grantKindIndividual`)
  if (kind === 'TEAM_ORG') return t(`${AK}.grantKindTeamOrg`)
  return kind ?? '—'
}

// ============================================================
// 付与一覧
// ============================================================
const grantsLoaded = ref(false)
const grantsLoading = ref(false)
const grants = ref<BetaPerkGrantDetail[]>([])
const totalElements = ref(0)

const filterGrantKind = ref<'' | BetaPerkGrantKind>('')
const filterPhase = ref<number | null>(null)
const filterReviewFlag = ref<'' | 'true' | 'false'>('')

const grantKindFilterOptions = computed(() => [
  { value: '', label: t(`${AK}.filterAll`) },
  { value: 'INDIVIDUAL', label: t(`${AK}.grantKindIndividual`) },
  { value: 'TEAM_ORG', label: t(`${AK}.grantKindTeamOrg`) },
])
const reviewFlagFilterOptions = computed(() => [
  { value: '', label: t(`${AK}.filterAll`) },
  { value: 'true', label: t(`${AK}.reviewFlagOnly`) },
  { value: 'false', label: t(`${AK}.reviewFlagNone`) },
])

async function loadGrants() {
  grantsLoading.value = true
  try {
    const res = await betaPerksApi.listGrants({
      grantKind: filterGrantKind.value || undefined,
      betaPhase: filterPhase.value ?? undefined,
      reviewFlag: filterReviewFlag.value === '' ? undefined : filterReviewFlag.value === 'true',
      page: 0,
      size: 50,
    })
    grants.value = res.data.content ?? []
    totalElements.value = res.data.totalElements ?? 0
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.grants.load')
  }
  finally {
    grantsLoading.value = false
    grantsLoaded.value = true
  }
}

// review_flag 行は警告色でハイライト（設計書 04 §B-3）
function grantRowClass(data: BetaPerkGrantDetail) {
  return data.reviewFlag ? 'bg-yellow-50 dark:bg-yellow-900/20' : ''
}

// === 取消ダイアログ ===
const revokeDialogVisible = ref(false)
const revokeTarget = ref<BetaPerkGrantDetail | null>(null)
const revokeForm = ref<{ reason: '' | 'TERMS_VIOLATION' | 'ACCOUNT_TRANSFER' | 'OTHER'; note: string }>({ reason: '', note: '' })
const revokeSubmitting = ref(false)
const revokeReasonOptions = computed(() => [
  { value: 'TERMS_VIOLATION', label: t(`${AK}.revokeReason.termsViolation`) },
  { value: 'ACCOUNT_TRANSFER', label: t(`${AK}.revokeReason.accountTransfer`) },
  { value: 'OTHER', label: t(`${AK}.revokeReason.other`) },
])

function openRevoke(grant: BetaPerkGrantDetail) {
  revokeTarget.value = grant
  revokeForm.value = { reason: '', note: '' }
  revokeDialogVisible.value = true
}

async function submitRevoke() {
  if (!revokeTarget.value?.grantId || !revokeForm.value.reason) return
  revokeSubmitting.value = true
  try {
    await betaPerksApi.revokeGrant(revokeTarget.value.grantId, {
      reason: revokeForm.value.reason,
      note: revokeForm.value.note.trim() || undefined,
    })
    notification.success(t(`${AK}.revokeSuccess`))
    revokeDialogVisible.value = false
    await loadGrants()
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.revoke')
  }
  finally {
    revokeSubmitting.value = false
  }
}

// === 延長ダイアログ ===
const extendDialogVisible = ref(false)
const extendTarget = ref<BetaPerkGrantDetail | null>(null)
const extendMonths = ref<number | null>(12)
const extendSubmitting = ref(false)

function openExtend(grant: BetaPerkGrantDetail) {
  extendTarget.value = grant
  extendMonths.value = 12
  extendDialogVisible.value = true
}

const extendValid = computed(() => extendMonths.value != null && extendMonths.value >= 1 && extendMonths.value <= 24)

async function submitExtend() {
  if (!extendTarget.value?.grantId || !extendValid.value || extendMonths.value == null) return
  extendSubmitting.value = true
  try {
    await betaPerksApi.extendGrant(extendTarget.value.grantId, { extensionMonths: extendMonths.value })
    notification.success(t(`${AK}.extendSuccess`))
    extendDialogVisible.value = false
    await loadGrants()
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.extend')
  }
  finally {
    extendSubmitting.value = false
  }
}

// === 審査フラグダイアログ ===
const flagDialogVisible = ref(false)
const flagTarget = ref<BetaPerkGrantDetail | null>(null)
const flagNote = ref('')
const flagSubmitting = ref(false)

function openFlag(grant: BetaPerkGrantDetail) {
  flagTarget.value = grant
  flagNote.value = ''
  flagDialogVisible.value = true
}

async function submitFlag() {
  if (!flagTarget.value?.grantId) return
  flagSubmitting.value = true
  try {
    await betaPerksApi.flagReview(flagTarget.value.grantId, { note: flagNote.value.trim() || undefined })
    notification.success(t(`${AK}.flagSuccess`))
    flagDialogVisible.value = false
    await loadGrants()
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.flag')
  }
  finally {
    flagSubmitting.value = false
  }
}

// === 審査解決 ===
const resolvingId = ref<string | null>(null)
async function resolveReview(grant: BetaPerkGrantDetail) {
  if (!grant.grantId) return
  if (!confirm(t(`${AK}.resolveConfirm`))) return
  resolvingId.value = grant.grantId
  try {
    await betaPerksApi.resolveReview(grant.grantId)
    notification.success(t(`${AK}.resolveSuccess`))
    await loadGrants()
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.resolve')
  }
  finally {
    resolvingId.value = null
  }
}

// === 手動付与ダイアログ ===
const grantDialogVisible = ref(false)
const grantForm = ref<{
  grantKind: BetaPerkGrantKind
  betaPhase: number | null
  scopeKind: BetaPerkScopeKind
  scopeId: number | null
  skipCriteriaCheck: boolean
  note: string
}>({ grantKind: 'TEAM_ORG', betaPhase: 2, scopeKind: 'TEAM', scopeId: null, skipCriteriaCheck: false, note: '' })
const grantSubmitting = ref(false)

const grantKindOptions = computed(() => [
  { value: 'INDIVIDUAL', label: t(`${AK}.grantKindIndividual`) },
  { value: 'TEAM_ORG', label: t(`${AK}.grantKindTeamOrg`) },
])
// grantKind に整合する scopeKind のみ選ばせる（BETA_PERK_007 不整合 422 を未然防止）
const scopeKindOptions = computed<BetaPerkScopeKind[]>(() =>
  grantForm.value.grantKind === 'INDIVIDUAL' ? ['USER'] : ['TEAM', 'ORG'],
)
watch(() => grantForm.value.grantKind, (kind) => {
  grantForm.value.scopeKind = kind === 'INDIVIDUAL' ? 'USER' : 'TEAM'
})

// skip=ON のとき note 必須（空なら付与不可・設計書 04 §B-3）
const grantBlocked = computed(() =>
  grantForm.value.scopeId == null
  || grantForm.value.betaPhase == null
  || grantForm.value.betaPhase < 1
  || grantForm.value.betaPhase > 4
  || (grantForm.value.skipCriteriaCheck && !grantForm.value.note.trim()),
)

function openGrantDialog() {
  grantForm.value = { grantKind: 'TEAM_ORG', betaPhase: 2, scopeKind: 'TEAM', scopeId: null, skipCriteriaCheck: false, note: '' }
  grantDialogVisible.value = true
}

async function submitGrant() {
  if (grantBlocked.value || grantForm.value.scopeId == null || grantForm.value.betaPhase == null) return
  grantSubmitting.value = true
  try {
    await betaPerksApi.createGrant({
      grantKind: grantForm.value.grantKind,
      betaPhase: grantForm.value.betaPhase,
      scopeKind: grantForm.value.scopeKind,
      scopeId: grantForm.value.scopeId,
      skipCriteriaCheck: grantForm.value.skipCriteriaCheck,
      note: grantForm.value.note.trim() || undefined,
    })
    notification.success(t(`${AK}.grantSuccess`))
    grantDialogVisible.value = false
    await loadGrants()
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.grant')
  }
  finally {
    grantSubmitting.value = false
  }
}

// ============================================================
// 付与候補（dry-run・Phase1 は TEAM_ORG のみ）
// ============================================================
const candidatesLoaded = ref(false)
const candidatesLoading = ref(false)
const candidates = ref<BetaPerkCandidate[]>([])
const candPhase = ref<number | null>(2)

async function loadCandidates() {
  candidatesLoading.value = true
  try {
    const res = await betaPerksApi.listCandidates({
      grantKind: 'TEAM_ORG',
      betaPhase: candPhase.value ?? undefined,
      page: 0,
      size: 50,
    })
    candidates.value = res.data ?? []
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.candidates.load')
  }
  finally {
    candidatesLoading.value = false
    candidatesLoaded.value = true
  }
}

// ============================================================
// 条件マスタ（betaPhase × grantKind の GET/PUT）
// ============================================================
const critPhase = ref<number | null>(2)
const critGrantKind = ref<BetaPerkGrantKind>('TEAM_ORG')
const critLoading = ref(false)
const critLoaded = ref(false)
const critNotFound = ref(false)
const critSubmitting = ref(false)
const critForm = ref<{
  evaluationWindowDays: number | null
  minActiveDays: number | null
  minActiveMembers: number | null
  minMembershipTenureDays: number | null
  enabled: boolean
}>({ evaluationWindowDays: 30, minActiveDays: null, minActiveMembers: null, minMembershipTenureDays: null, enabled: true })

function applyCriteria(c: BetaPerkCriteriaResponse) {
  critForm.value = {
    evaluationWindowDays: c.evaluationWindowDays ?? 30,
    minActiveDays: c.minActiveDays ?? null,
    minActiveMembers: c.minActiveMembers ?? null,
    minMembershipTenureDays: c.minMembershipTenureDays ?? null,
    enabled: c.enabled ?? true,
  }
}

function resetCriteriaForm() {
  critForm.value = { evaluationWindowDays: 30, minActiveDays: null, minActiveMembers: null, minMembershipTenureDays: null, enabled: true }
}

async function loadCriteria() {
  if (critPhase.value == null) return
  critLoading.value = true
  critNotFound.value = false
  try {
    const res = await betaPerksApi.getCriteria(critPhase.value, critGrantKind.value)
    applyCriteria(res.data)
    critLoaded.value = true
  }
  catch (err) {
    // 未定義（BETA_PERK_010 / 404）は「エラー」ではなく新規 PUT 導線を出す想定状態。
    // それ以外は握りつぶさず handleApiError で表示する（対処療法禁止）。
    const e = err as { statusCode?: number; data?: { error?: { code?: string } } }
    if (e?.data?.error?.code === 'BETA_PERK_010' || e?.statusCode === 404) {
      critNotFound.value = true
      critLoaded.value = true
      resetCriteriaForm()
    }
    else {
      handleApiError(err, 'billing.betaPerks.admin.criteria.load')
    }
  }
  finally {
    critLoading.value = false
  }
}

// 全指標 NULL は「無条件付与」防止のため送信前に拒否（BE009 相当・設計書 02 §4.6）
const criteriaAllNull = computed(() =>
  critForm.value.minActiveDays == null
  && critForm.value.minActiveMembers == null
  && critForm.value.minMembershipTenureDays == null,
)
const criteriaWindowValid = computed(() =>
  critForm.value.evaluationWindowDays != null
  && critForm.value.evaluationWindowDays >= 1
  && critForm.value.evaluationWindowDays <= 365,
)
const criteriaBlocked = computed(() => criteriaAllNull.value || !criteriaWindowValid.value)

async function saveCriteria() {
  if (critPhase.value == null || criteriaBlocked.value || critForm.value.evaluationWindowDays == null) return
  critSubmitting.value = true
  try {
    const res = await betaPerksApi.upsertCriteria(critPhase.value, critGrantKind.value, {
      evaluationWindowDays: critForm.value.evaluationWindowDays,
      minActiveDays: critForm.value.minActiveDays ?? undefined,
      minActiveMembers: critForm.value.minActiveMembers ?? undefined,
      minMembershipTenureDays: critForm.value.minMembershipTenureDays ?? undefined,
      enabled: critForm.value.enabled,
    })
    applyCriteria(res.data)
    critNotFound.value = false
    notification.success(t(`${AK}.criteriaSaveSuccess`))
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.admin.criteria.save')
  }
  finally {
    critSubmitting.value = false
  }
}

// タブ切替で候補は初回のみ遅延ロード（付与一覧は onMounted で先読み）
watch(activeTab, (idx) => {
  if (idx === 1 && !candidatesLoaded.value) loadCandidates()
})

onMounted(() => {
  loadGrants()
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader :title="t('billing.betaPerks.admin.title')" help @help="helpVisible = true" />

    <TabView v-model:active-index="activeTab">
      <!-- 付与一覧 -->
      <TabPanel :header="t('billing.betaPerks.admin.tabGrants')" value="0">
        <div class="mb-4 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.filterGrantKind') }}</label>
            <Select
              v-model="filterGrantKind"
              :options="grantKindFilterOptions"
              option-label="label"
              option-value="value"
              class="w-44"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.filterPhase') }}</label>
            <InputNumber v-model="filterPhase" class="w-28" :use-grouping="false" :min="1" :max="4" :placeholder="t('billing.betaPerks.admin.filterAll')" />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.filterReviewFlag') }}</label>
            <Select
              v-model="filterReviewFlag"
              :options="reviewFlagFilterOptions"
              option-label="label"
              option-value="value"
              class="w-44"
            />
          </div>
          <Button :label="t('button.search')" icon="pi pi-search" :loading="grantsLoading" @click="loadGrants" />
          <div class="ml-auto">
            <Button :label="t('billing.betaPerks.admin.grantCta')" icon="pi pi-plus" @click="openGrantDialog" />
          </div>
        </div>

        <PageLoading v-if="!grantsLoaded" />
        <DataTable
          v-else
          :value="grants"
          data-key="grantId"
          striped-rows
          :loading="grantsLoading"
          :row-class="grantRowClass"
        >
          <template #empty>
            <p class="py-6 text-center text-sm text-surface-500">{{ t('billing.betaPerks.admin.grantsEmpty') }}</p>
          </template>
          <Column :header="t('billing.betaPerks.admin.colScope')">
            <template #body="{ data }">
              <div class="font-medium">{{ data.scopeDisplayName ?? '—' }}</div>
              <div class="text-xs text-surface-400">{{ data.scopeKind }} #{{ data.scopeId }}</div>
            </template>
          </Column>
          <Column :header="t('billing.betaPerks.admin.colGrantKind')" style="width: 130px">
            <template #body="{ data }">{{ grantKindLabel(data.grantKind) }}</template>
          </Column>
          <Column field="betaPhase" :header="t('billing.betaPerks.admin.colPhase')" style="width: 90px" />
          <Column :header="t('billing.betaPerks.admin.colGrantedBy')" style="width: 130px">
            <template #body="{ data }">{{ data.grantedByName ?? t('billing.betaPerks.admin.systemGrantedBy') }}</template>
          </Column>
          <Column :header="t('billing.betaPerks.admin.colValidUntil')" style="width: 150px">
            <template #body="{ data }">
              {{ data.validUntil == null ? t('billing.betaPerks.admin.validUntilNone') : formatDate(data.validUntil) }}
            </template>
          </Column>
          <Column :header="t('billing.betaPerks.admin.colReview')" style="width: 110px">
            <template #body="{ data }">
              <Tag v-if="data.reviewFlag" :value="t('billing.betaPerks.admin.reviewFlagged')" severity="warn" />
              <span v-else class="text-surface-300">—</span>
            </template>
          </Column>
          <Column :header="t('billing.betaPerks.admin.colStatus')" style="width: 100px">
            <template #body="{ data }">
              <Tag
                :value="data.revokedAt ? t('billing.betaPerks.admin.statusRevoked') : t('billing.betaPerks.admin.statusActive')"
                :severity="data.revokedAt ? 'danger' : 'success'"
              />
            </template>
          </Column>
          <Column header="" style="width: 200px">
            <template #body="{ data }">
              <div class="flex gap-1">
                <Button
                  v-if="data.reviewFlag"
                  v-tooltip="t('billing.betaPerks.admin.resolveReviewCta')"
                  icon="pi pi-check"
                  size="small"
                  severity="success"
                  text
                  :loading="resolvingId === data.grantId"
                  @click="resolveReview(data)"
                />
                <Button
                  v-else-if="!data.revokedAt"
                  v-tooltip="t('billing.betaPerks.admin.flagReviewCta')"
                  icon="pi pi-flag"
                  size="small"
                  severity="warn"
                  text
                  @click="openFlag(data)"
                />
                <Button
                  v-if="!data.revokedAt && data.grantKind === 'TEAM_ORG'"
                  v-tooltip="t('billing.betaPerks.admin.extendCta')"
                  icon="pi pi-calendar-plus"
                  size="small"
                  severity="secondary"
                  text
                  @click="openExtend(data)"
                />
                <Button
                  v-if="!data.revokedAt"
                  v-tooltip="t('billing.betaPerks.admin.revokeCta')"
                  icon="pi pi-times-circle"
                  size="small"
                  severity="danger"
                  text
                  @click="openRevoke(data)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </TabPanel>

      <!-- 付与候補（dry-run） -->
      <TabPanel :header="t('billing.betaPerks.admin.tabCandidates')" value="1">
        <Message severity="info" :closable="false" class="mb-4 text-sm">
          {{ t('billing.betaPerks.admin.candidatesTeamOrgNote') }}
        </Message>
        <div class="mb-4 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.filterPhase') }}</label>
            <InputNumber v-model="candPhase" class="w-28" :use-grouping="false" :min="1" :max="4" />
          </div>
          <Button :label="t('button.search')" icon="pi pi-search" :loading="candidatesLoading" @click="loadCandidates" />
        </div>

        <PageLoading v-if="candidatesLoading && !candidatesLoaded" />
        <DataTable v-else :value="candidates" data-key="scopeId" striped-rows :loading="candidatesLoading">
          <template #empty>
            <p class="py-6 text-center text-sm text-surface-500">{{ t('billing.betaPerks.admin.candidatesEmpty') }}</p>
          </template>
          <Column :header="t('billing.betaPerks.admin.candidateScope')">
            <template #body="{ data }">
              <div class="font-medium">{{ data.displayName ?? '—' }}</div>
              <div class="text-xs text-surface-400">{{ data.scopeKind }} #{{ data.scopeId }}</div>
            </template>
          </Column>
          <Column :header="t('billing.betaPerks.admin.candidateMetrics')">
            <template #body="{ data }">
              <div class="flex flex-wrap gap-2">
                <span
                  v-for="(m, i) in data.metrics ?? []"
                  :key="i"
                  class="rounded bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-800"
                >
                  {{ metricLabel(m.metricKey) }} {{ m.actual }} / {{ m.required }}
                </span>
              </div>
            </template>
          </Column>
        </DataTable>
      </TabPanel>

      <!-- 条件マスタ -->
      <TabPanel :header="t('billing.betaPerks.admin.tabCriteria')" value="2">
        <div class="mb-4 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.criteriaPhaseLabel') }}</label>
            <InputNumber v-model="critPhase" class="w-28" :use-grouping="false" :min="1" :max="4" />
          </div>
          <div>
            <label class="mb-1 block text-xs font-medium">{{ t('billing.betaPerks.admin.criteriaGrantKindLabel') }}</label>
            <Select
              v-model="critGrantKind"
              :options="grantKindOptions"
              option-label="label"
              option-value="value"
              class="w-44"
            />
          </div>
          <Button :label="t('billing.betaPerks.admin.criteriaLoad')" icon="pi pi-search" :loading="critLoading" @click="loadCriteria" />
        </div>

        <SectionCard v-if="critLoaded">
          <Message v-if="critNotFound" severity="warn" :closable="false" class="mb-4 text-sm">
            {{ t('billing.betaPerks.admin.criteriaNotFound') }}
          </Message>
          <div class="grid max-w-xl gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.evaluationWindowDays') }}</label>
              <InputNumber v-model="critForm.evaluationWindowDays" class="w-full" :use-grouping="false" :min="1" :max="365" />
              <p class="mt-1 text-xs text-surface-400">{{ t('billing.betaPerks.admin.criteriaWindowRange') }}</p>
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.minActiveDays') }}</label>
              <InputNumber v-model="critForm.minActiveDays" class="w-full" :use-grouping="false" :min="0" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.minMembershipTenureDays') }}</label>
              <InputNumber v-model="critForm.minMembershipTenureDays" class="w-full" :use-grouping="false" :min="0" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.minActiveMembers') }}</label>
              <InputNumber v-model="critForm.minActiveMembers" class="w-full" :use-grouping="false" :min="0" />
            </div>
            <div class="flex items-center gap-2">
              <ToggleSwitch v-model="critForm.enabled" />
              <span class="text-sm">{{ t('billing.betaPerks.admin.enabledLabel') }}</span>
            </div>
            <Message v-if="criteriaAllNull" severity="warn" :closable="false" class="text-xs">
              {{ t('billing.betaPerks.admin.criteriaMinOneMetric') }}
            </Message>
            <div>
              <Button
                :label="t('billing.betaPerks.admin.criteriaSave')"
                :loading="critSubmitting"
                :disabled="criteriaBlocked"
                @click="saveCriteria"
              />
            </div>
          </div>
        </SectionCard>
      </TabPanel>
    </TabView>

    <!-- 手動付与ダイアログ -->
    <Dialog v-model:visible="grantDialogVisible" :header="t('billing.betaPerks.admin.grantDialogTitle')" :style="{ width: '480px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.grantKindLabel') }}</label>
          <Select v-model="grantForm.grantKind" :options="grantKindOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.betaPhaseLabel') }}</label>
          <InputNumber v-model="grantForm.betaPhase" class="w-full" :use-grouping="false" :min="1" :max="4" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.scopeKindLabel') }}</label>
          <Select v-model="grantForm.scopeKind" :options="scopeKindOptions" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.scopeIdLabel') }}</label>
          <InputNumber v-model="grantForm.scopeId" class="w-full" :use-grouping="false" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="grantForm.skipCriteriaCheck" />
          <span class="text-sm">{{ t('billing.betaPerks.admin.skipCriteriaCheck') }}</span>
        </div>
        <Message v-if="grantForm.skipCriteriaCheck" severity="warn" :closable="false" class="text-xs">
          {{ t('billing.betaPerks.admin.skipCriteriaWarning') }}
        </Message>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.noteLabel') }}</label>
          <Textarea v-model="grantForm.note" class="w-full" rows="2" :placeholder="t('billing.betaPerks.admin.notePlaceholder')" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="grantDialogVisible = false" />
        <Button :label="t('billing.betaPerks.admin.grantSubmit')" :loading="grantSubmitting" :disabled="grantBlocked" @click="submitGrant" />
      </template>
    </Dialog>

    <!-- 取消ダイアログ -->
    <Dialog v-model:visible="revokeDialogVisible" :header="t('billing.betaPerks.admin.revokeDialogTitle')" :style="{ width: '440px' }" modal :draggable="false">
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.revokeReasonLabel') }}</label>
          <Select v-model="revokeForm.reason" :options="revokeReasonOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.revokeNoteLabel') }}</label>
          <Textarea v-model="revokeForm.note" class="w-full" rows="2" :placeholder="t('billing.betaPerks.admin.revokeNotePlaceholder')" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="revokeDialogVisible = false" />
        <Button :label="t('billing.betaPerks.admin.revokeCta')" severity="danger" :loading="revokeSubmitting" :disabled="!revokeForm.reason" @click="submitRevoke" />
      </template>
    </Dialog>

    <!-- 延長ダイアログ -->
    <Dialog v-model:visible="extendDialogVisible" :header="t('billing.betaPerks.admin.extendDialogTitle')" :style="{ width: '420px' }" modal :draggable="false">
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.extendMonthsLabel') }}</label>
          <InputNumber v-model="extendMonths" class="w-full" :use-grouping="false" :min="1" :max="24" />
          <p class="mt-1 text-xs text-surface-400">{{ t('billing.betaPerks.admin.extendMonthsHelp') }}</p>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="extendDialogVisible = false" />
        <Button :label="t('billing.betaPerks.admin.extendCta')" :loading="extendSubmitting" :disabled="!extendValid" @click="submitExtend" />
      </template>
    </Dialog>

    <!-- 審査フラグダイアログ -->
    <Dialog v-model:visible="flagDialogVisible" :header="t('billing.betaPerks.admin.flagDialogTitle')" :style="{ width: '420px' }" modal :draggable="false">
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('billing.betaPerks.admin.flagNoteLabel') }}</label>
          <Textarea v-model="flagNote" class="w-full" rows="2" :placeholder="t('billing.betaPerks.admin.flagNotePlaceholder')" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="flagDialogVisible = false" />
        <Button :label="t('billing.betaPerks.admin.flagReviewCta')" severity="warn" :loading="flagSubmitting" @click="submitFlag" />
      </template>
    </Dialog>

    <!-- 使い方ガイド（/手助け・betaPerks 4カード） -->
    <BillingHelpDialog v-model:visible="helpVisible" variant="betaPerks" />
  </div>
</template>
