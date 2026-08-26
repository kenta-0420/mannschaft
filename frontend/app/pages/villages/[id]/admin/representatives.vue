<script setup lang="ts">
/**
 * F17.1 Phase 2 U3 — 村代表委任 管理画面（`/villages/[id]/admin/representatives`）。
 *
 * Issue #2356: BE `VillageRepresentativeController`（grant/revoke/list）は完備済みだったが、
 * FE 入口が一切無く `VillageRepresentativeGrantDialog.vue` がどこからもマウントされない
 * 孤児コンポーネントになっていた。本画面はその Dialog を村長コンソールに正式に搭載する。
 *
 * # 金型
 *  一覧 + 追加 Dialog + 行内取消しボタンの構成は `recruit-categories.vue` を踏襲する。
 *  ただし「対象（チーム/組織）を選ぶ」ステップが挟まる点が recruit-categories と異なる
 *  （代表委任は村単位ではなく「TEAM/ORGANIZATION メンバーシップ単位」で発行するため）。
 *
 * # 認可
 *  - 画面自体（一覧閲覧）: `perms.isAdmin`（HEADMAN or ELDER）。既存コンソールの他カード
 *    （members / recruit-categories 等）と同じ基準に合わせる。
 *  - 委任の追加・取消し: `perms.isHeadman`（村長のみ）。
 *    注記: BE Service（`VillageRepresentativeService#ensureModerator`）は実際には
 *    HEADMAN/ELDER のどちらでも許可しており、長老が API を直接叩けば成功する。
 *    本画面はそれよりも狭く「村長のみ」に絞っている（意図的な設計判断・Issue #2356 指示）。
 *    長老に委任操作 UI も開放すべきかは、必要になった時点で村長コンソールの他カードと
 *    足並みを揃えて再検討する。
 *
 * # 対象（チーム/組織）の選び方
 *  代表委任 API は `membershipId`（village_memberships.id、TEAM/ORGANIZATION 種別）を要求する。
 *  一覧 API 自体には TEAM/ORG 選択肢が乗っていないため、`listMembers` を別途取得し
 *  USER 種別を除外して選択肢を作る。ページングは考慮せず先頭 100 件のみを対象とする
 *  （村の TEAM/ORG メンバーシップが 100 件を超える運用は現状想定外）。
 */
import { useVillageContext } from '~/composables/useVillageContext'
import { useVillageFeatureApi } from '~/composables/village/useVillageFeatureApi'
import { useVillageMembershipApi } from '~/composables/village/useVillageMembershipApi'
import type { MembershipResponse, VillageRepresentativeResponse } from '~/types/village'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const { formatDateTime } = useDatetime()
const { listRepresentatives, revokeRepresentative } = useVillageFeatureApi()
const { listMembers } = useVillageMembershipApi()
const { showSuccess, showError } = useNotification()

const villageId = computed<string>(() => String(route.params.id))

// 村本体・権限は親シェルから inject（再フェッチしない）
const { village, perms } = useVillageContext()

// =============================================================================
// エラー抽出（recruit-categories.vue / members.vue と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody & { error?: ApiErrorBody }
  status?: number
  statusCode?: number
  response?: { status?: number, _data?: ApiErrorBody & { error?: ApiErrorBody } }
}

function extractErrorCode(err: unknown): string | null {
  if (typeof err !== 'object' || err === null) return null
  const e = err as ApiErrorEnvelope
  const body = e.data ?? e.response?._data
  return body?.error?.code ?? body?.errorCode ?? body?.code ?? null
}

/** VILLAGE_052〜055（代表委任固有）は専用文言、それ以外は共通 village.error にフォールバック。 */
function translateError(code: string | null, fallback: string): string {
  if (code === 'VILLAGE_052' || code === 'VILLAGE_053' || code === 'VILLAGE_054' || code === 'VILLAGE_055') {
    return t(`village.error.${code}`)
  }
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    if (msg && msg !== key) return msg
  }
  return fallback
}

// =============================================================================
// 一覧（代表委任）
// =============================================================================

const representatives = ref<VillageRepresentativeResponse[]>([])
const repsLoading = ref(false)

async function loadRepresentatives() {
  repsLoading.value = true
  try {
    representatives.value = await listRepresentatives(villageId.value)
  }
  catch (err) {
    representatives.value = []
    showError(translateError(extractErrorCode(err), t('village.representative.loadFailed')))
  }
  finally {
    repsLoading.value = false
  }
}

// =============================================================================
// 対象（チーム/組織メンバーシップ）選択肢
// =============================================================================

const teamOrgMemberships = ref<MembershipResponse[]>([])
const membershipsLoading = ref(false)

async function loadTeamOrgMemberships() {
  membershipsLoading.value = true
  try {
    const res = await listMembers(villageId.value, { size: 100 })
    teamOrgMemberships.value = res.content.filter(m => m.subjectType !== 'USER')
  }
  catch {
    // 対象選択肢の取得失敗は一覧表示自体を止めない（追加操作のみ不可になる）。
    teamOrgMemberships.value = []
  }
  finally {
    membershipsLoading.value = false
  }
}

interface MembershipOption {
  value: string
  label: string
}

const membershipOptions = computed<MembershipOption[]>(() =>
  teamOrgMemberships.value.map(m => ({
    value: m.id,
    label: `${m.displayName ?? `#${m.subjectId}`}（${t(`village.subjectType.${m.subjectType}`)}）`,
  })),
)

/** membershipId → 表示ラベル（一覧テーブルの「対象」列で使う）。解決不可なら不明表記。 */
function membershipLabel(membershipId: string): string {
  const m = teamOrgMemberships.value.find(x => x.id === membershipId)
  if (!m) return t('village.representative.unknownTarget')
  return `${m.displayName ?? `#${m.subjectId}`}（${t(`village.subjectType.${m.subjectType}`)}）`
}

onMounted(() => {
  void loadRepresentatives()
  void loadTeamOrgMemberships()
})

// 親シェルは村取得をクライアントで行うため、権限確定が本ページのマウント後になりうる
// （recruit-categories.vue と同じ配慮）。
watch(village, (v) => {
  if (v) {
    void loadRepresentatives()
    void loadTeamOrgMemberships()
  }
})

// =============================================================================
// 追加（既存 VillageRepresentativeGrantDialog を流用）
// =============================================================================

const selectedMembershipId = ref<string | null>(null)
const grantDialogVisible = ref(false)

function openGrantDialog() {
  if (!selectedMembershipId.value) return
  grantDialogVisible.value = true
}

function onGranted() {
  void loadRepresentatives()
}

// =============================================================================
// 取消し
// =============================================================================

const revokeDialogVisible = ref(false)
const revokeTarget = ref<VillageRepresentativeResponse | null>(null)
const revoking = ref(false)

function openRevokeDialog(rep: VillageRepresentativeResponse) {
  revokeTarget.value = rep
  revokeDialogVisible.value = true
}

function closeRevokeDialog() {
  revokeDialogVisible.value = false
  revokeTarget.value = null
  revoking.value = false
}

async function confirmRevoke() {
  const target = revokeTarget.value
  if (!target || revoking.value) return
  revoking.value = true
  try {
    await revokeRepresentative(villageId.value, target.id)
    showSuccess(t('village.representative.revokeSuccess'))
    revokeDialogVisible.value = false
    await loadRepresentatives()
  }
  catch (err) {
    showError(translateError(extractErrorCode(err), t('village.error.generic')))
  }
  finally {
    revoking.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-6">
    <PageHeader
      :title="t('village.representative.title')"
      size="sm"
      :back-to="`/villages/${villageId}/admin`"
    >
      <template v-if="village" #actions>
        <span class="text-sm text-surface-500">{{ village.name }}</span>
      </template>
    </PageHeader>

    <!-- 権限不足（VILLAGER / VISITOR） -->
    <Message
      v-if="!perms.isAdmin"
      severity="warn"
      :closable="false"
      data-testid="village-representative-access-denied"
    >
      {{ t('village.admin.accessDenied') }}
    </Message>

    <template v-else>
      <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
        {{ t('village.representative.subtitle') }}
      </p>

      <!-- 追加（村長のみ） -->
      <SectionCard v-if="perms.isHeadman" class="mb-4">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
          <div class="flex-1">
            <label for="representative-target-select" class="mb-1 block text-sm font-medium">
              {{ t('village.representative.target') }}
            </label>
            <Select
              id="representative-target-select"
              v-model="selectedMembershipId"
              :options="membershipOptions"
              option-label="label"
              option-value="value"
              :placeholder="t('village.representative.targetPlaceholder')"
              :disabled="membershipsLoading || membershipOptions.length === 0"
              class="w-full"
              data-testid="representative-target-select"
            />
            <p v-if="!membershipsLoading && membershipOptions.length === 0" class="mt-1 text-xs text-surface-500">
              {{ t('village.representative.noTargets') }}
            </p>
          </div>
          <Button
            :label="t('village.representative.grant')"
            icon="pi pi-user-plus"
            :disabled="!selectedMembershipId"
            data-testid="representative-add"
            @click="openGrantDialog"
          />
        </div>
      </SectionCard>

      <SectionCard>
        <div v-if="repsLoading" class="py-12 text-center text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" aria-hidden="true" />
        </div>

        <DataTable
          v-else
          :value="representatives"
          data-key="id"
          striped-rows
          class="text-sm"
          data-testid="representative-table"
        >
          <template #empty>
            <div class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400">
              <i class="pi pi-id-card text-4xl" aria-hidden="true" />
              <p class="text-sm">
                {{ t('village.representative.empty') }}
              </p>
            </div>
          </template>

          <!-- 対象（チーム/組織） -->
          <Column :header="t('village.representative.target')" style="min-width: 12rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              {{ membershipLabel(row.membershipId) }}
            </template>
          </Column>

          <!-- 代表ユーザー -->
          <Column :header="t('village.representative.representativeUser')" style="min-width: 10rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              {{ row.representativeDisplayName ?? `#${row.representativeUserId}` }}
            </template>
          </Column>

          <!-- 委任元 -->
          <Column :header="t('village.representative.grantedBy')" style="min-width: 10rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              {{ row.grantedByDisplayName ?? `#${row.grantedByUserId}` }}
            </template>
          </Column>

          <!-- 委任日時 -->
          <Column :header="t('village.representative.grantedAt')" style="width: 10rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              {{ formatDateTime(row.grantedAt) }}
            </template>
          </Column>

          <!-- メモ（ユーザー入力の自由文のため $t() を通さず生値で描画） -->
          <Column :header="t('village.representative.note')" style="min-width: 10rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              <span class="text-surface-500">{{ row.note ?? '—' }}</span>
            </template>
          </Column>

          <!-- 操作（村長のみ取消し可） -->
          <Column v-if="perms.isHeadman" style="width: 5rem">
            <template #body="{ data: row }: { data: VillageRepresentativeResponse }">
              <Button
                icon="pi pi-times"
                text
                rounded
                size="small"
                severity="danger"
                :aria-label="t('village.representative.revoke')"
                :data-testid="`representative-revoke-${row.id}`"
                @click="openRevokeDialog(row)"
              />
            </template>
          </Column>
        </DataTable>
      </SectionCard>
    </template>

    <!-- 追加 Dialog（孤児化していた既存コンポーネントを流用） -->
    <VillageRepresentativeGrantDialog
      v-if="selectedMembershipId"
      v-model:visible="grantDialogVisible"
      :village-id="villageId"
      :membership-id="selectedMembershipId"
      @granted="onGranted"
    />

    <!-- 取消し確認 Dialog -->
    <Dialog
      v-model:visible="revokeDialogVisible"
      modal
      :header="t('village.representative.confirmRevokeTitle')"
      :style="{ width: '28rem' }"
      :draggable="false"
      :closable="!revoking"
      data-testid="representative-revoke-dialog"
    >
      <p v-if="revokeTarget" class="text-sm">
        {{ t('village.representative.confirmRevokeBody', {
          name: revokeTarget.representativeDisplayName ?? `#${revokeTarget.representativeUserId}`,
        }) }}
      </p>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          :disabled="revoking"
          @click="closeRevokeDialog"
        />
        <Button
          :label="t('village.representative.revoke')"
          severity="danger"
          :loading="revoking"
          data-testid="representative-revoke-confirm"
          @click="confirmRevoke"
        />
      </template>
    </Dialog>
  </div>
</template>
