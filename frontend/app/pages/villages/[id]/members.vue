<script setup lang="ts">
/**
 * F17.1 村機能 — メンバー一覧ページ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.3 / §4.11
 *
 * 画面構成:
 *   1. VillageHeader（FE2 成果物） — activeTab="members"
 *   2. メンバー一覧テーブル（VillageMembersTable 子コンポ）
 *   3. ロール変更 Dialog（VillageMembersRoleDialog 子コンポ・HEADMAN のみ）
 *   4. BAN 確認 Dialog（VillageMembersBanDialog 子コンポ・HEADMAN のみ）
 *   5. 通報 Dialog（VillageReportDialog 共通コンポ）
 *   6. 村本体編集 Dialog（VillageEditDialog 共通コンポ）
 *
 * 権限:
 *   - 自分が HEADMAN（村長）の場合のみ「ロール変更」「BAN」操作ボタンを表示。
 *   - ELDER（長老）は本画面の範囲外（管理 UI は別途）。
 *
 * 通報:
 *   - 各メンバー行の通報ボタン → VillageReportDialog
 *     (targetType=MEMBERSHIP, targetRefId=membership.id)
 *   - VillageHeader の通報ボタン → VillageReportDialog
 *     (targetType=VILLAGE, targetRefId=village.id)
 *
 * リファクタ第 12 弾でテンプレート部を以下の子コンポーネントに分割。
 * 本ページは API 呼び出し・状態管理・各種ハンドラに専念する：
 *   - components/villages/members/VillageMembersTable.vue
 *   - components/villages/members/VillageMembersRoleDialog.vue
 *   - components/villages/members/VillageMembersBanDialog.vue
 */
import VillageHeader from '~/components/VillageHeader.vue'
import VillageReportDialog from '~/components/VillageReportDialog.vue'
import VillageMembersBanDialog from '~/components/villages/members/VillageMembersBanDialog.vue'
import VillageMembersRoleDialog from '~/components/villages/members/VillageMembersRoleDialog.vue'
import VillageMembersTable from '~/components/villages/members/VillageMembersTable.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type {
  MembershipResponse,
  VillageReportTargetType,
  VillageResponse,
  VillageRole,
} from '~/types/village'

definePageMeta({
  layout: 'default',
  middleware: 'auth',
  key: route => route.fullPath,
})

const route = useRoute()
const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError, showWarn } = useNotification()
const authStore = useAuthStore()

const villageId = computed<string>(() => String(route.params.id))

// =============================================================================
// 定数
// =============================================================================

const PAGE_SIZE = 50
const BAN_REASON_MAX = 200

/** ロール変更で選択できるロール（VISITOR は通常昇格させない設計 §5.1） */
const ASSIGNABLE_ROLES: VillageRole[] = ['ELDER', 'VILLAGER']

interface RoleOption {
  value: VillageRole
  label: string
}

const roleOptions = computed<RoleOption[]>(() =>
  ASSIGNABLE_ROLES.map(value => ({
    value,
    label: t(`village.role.${value}`),
  })),
)

// =============================================================================
// 状態
// =============================================================================

const village = ref<VillageResponse | null>(null)
const villageLoading = ref(false)

const members = ref<MembershipResponse[]>([])
const membersLoading = ref(false)
const totalElements = ref(0)
const page = ref(0)

// Dialog 状態 — ロール変更
const roleDialogVisible = ref(false)
const roleDialogTarget = ref<MembershipResponse | null>(null)
const roleDialogNewRole = ref<VillageRole | null>(null)
const roleSubmitting = ref(false)

// Dialog 状態 — BAN
const banDialogVisible = ref(false)
const banDialogTarget = ref<MembershipResponse | null>(null)
const banDialogReason = ref('')
const banSubmitting = ref(false)

// Dialog 状態 — 通報
const reportDialogVisible = ref(false)
const reportTargetType = ref<VillageReportTargetType>('MEMBERSHIP')
const reportTargetRefId = ref<string>('')

// =============================================================================
// 派生値
// =============================================================================

/** 自分の userId（auth store 由来） */
const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

/**
 * 自分が HEADMAN かどうか。
 *
 * 判定方針:
 *   1. VillageResponse.myRole が 'HEADMAN' ならそれを採用
 *   2. それが取得できない場合は members 配列から自分の USER membership を探す
 */
const isHeadman = computed<boolean>(() => {
  if (village.value?.myRole === 'HEADMAN') return true
  if (currentUserId.value == null) return false
  return members.value.some(
    m => m.subjectType === 'USER' && m.subjectId === currentUserId.value && m.role === 'HEADMAN' && !m.isBanned,
  )
})

// =============================================================================
// 表示ヘルパ（本体に残すもの — 自身の判定など）
// =============================================================================

/** 自分自身の membership 行かどうか（自分への操作は禁止） */
function isSelfMembership(m: MembershipResponse): boolean {
  if (currentUserId.value == null) return false
  return m.subjectType === 'USER' && m.subjectId === currentUserId.value
}

// =============================================================================
// エラー抽出（FE3 と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody
  status?: number
  statusCode?: number
  response?: { status?: number, _data?: ApiErrorBody }
}

function extractApiError(err: unknown): { code: string | null, status: number | null } {
  if (typeof err !== 'object' || err === null) {
    return { code: null, status: null }
  }
  const e = err as ApiErrorEnvelope
  const body: ApiErrorBody | undefined = e.data ?? e.response?._data
  const code = body?.errorCode ?? body?.code ?? null
  const status = e.status ?? e.statusCode ?? e.response?.status ?? null
  return { code, status }
}

function translateApiError(code: string | null, status: number | null): string {
  if (status === 429) return t('village.error.VILLAGE_009')
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    if (msg && msg !== key) return msg
  }
  return t('village.error.generic')
}

// =============================================================================
// データロード
// =============================================================================

async function loadVillage() {
  villageLoading.value = true
  try {
    village.value = await villageApi.getVillage(villageId.value)
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    villageLoading.value = false
  }
}

async function loadMembers() {
  membersLoading.value = true
  try {
    const res = await villageApi.listMembers(villageId.value, {
      page: page.value,
      size: PAGE_SIZE,
    })
    members.value = res.content
    totalElements.value = res.totalElements
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    membersLoading.value = false
  }
}

function onPageChange(event: { page: number }) {
  page.value = event.page
  void loadMembers()
}

// =============================================================================
// ロール変更
// =============================================================================

function openRoleDialog(m: MembershipResponse) {
  if (!isHeadman.value || isSelfMembership(m)) return
  roleDialogTarget.value = m
  // 現在のロールから別ロールへ初期化（VILLAGER → ELDER、ELDER → VILLAGER）
  roleDialogNewRole.value = m.role === 'ELDER' ? 'VILLAGER' : 'ELDER'
  roleDialogVisible.value = true
}

function closeRoleDialog() {
  roleDialogVisible.value = false
  roleDialogTarget.value = null
  roleDialogNewRole.value = null
  roleSubmitting.value = false
}

async function submitRoleChange() {
  const target = roleDialogTarget.value
  const newRole = roleDialogNewRole.value
  if (!target || !newRole || roleSubmitting.value) return
  // ロールに変化がない場合は何もしない
  if (target.role === newRole) {
    closeRoleDialog()
    return
  }
  roleSubmitting.value = true
  try {
    await villageApi.changeRole(villageId.value, target.id, { role: newRole })
    showSuccess(t('village.success.roleChanged'))
    closeRoleDialog()
    await loadMembers()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    if (status === 403 || code === 'VILLAGE_024') {
      showWarn(t('village.error.VILLAGE_024'))
    }
    else {
      showError(translateApiError(code, status))
    }
    roleSubmitting.value = false
  }
}

// =============================================================================
// BAN
// =============================================================================

function openBanDialog(m: MembershipResponse) {
  if (!isHeadman.value || isSelfMembership(m)) return
  banDialogTarget.value = m
  banDialogReason.value = ''
  banDialogVisible.value = true
}

function closeBanDialog() {
  banDialogVisible.value = false
  banDialogTarget.value = null
  banDialogReason.value = ''
  banSubmitting.value = false
}

const banReasonError = computed<string | null>(() => {
  if (banDialogReason.value.length > BAN_REASON_MAX) {
    return t('village.error.VILLAGE_029')
  }
  return null
})

const canSubmitBan = computed<boolean>(() => {
  if (banSubmitting.value) return false
  if (banReasonError.value) return false
  return banDialogTarget.value != null
})

async function submitBan() {
  const target = banDialogTarget.value
  if (!target || !canSubmitBan.value) return
  banSubmitting.value = true
  try {
    await villageApi.banMember(villageId.value, target.id, {
      reason: banDialogReason.value.trim() === '' ? null : banDialogReason.value.trim(),
    })
    showSuccess(t('village.success.banned'))
    closeBanDialog()
    await loadMembers()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    if (status === 403 || code === 'VILLAGE_024') {
      showWarn(t('village.error.VILLAGE_024'))
    }
    else {
      showError(translateApiError(code, status))
    }
    banSubmitting.value = false
  }
}

// =============================================================================
// 通報
// =============================================================================

function openMemberReportDialog(m: MembershipResponse) {
  reportTargetType.value = 'MEMBERSHIP'
  reportTargetRefId.value = m.id
  reportDialogVisible.value = true
}

function openVillageReportDialog() {
  if (!village.value) return
  reportTargetType.value = 'VILLAGE'
  reportTargetRefId.value = village.value.id
  reportDialogVisible.value = true
}

// =============================================================================
// VillageHeader からの emit ハンドラ
// =============================================================================

async function handleJoin() {
  if (!village.value) return
  if (currentUserId.value == null) {
    showError(t('village.error.generic'))
    return
  }
  try {
    await villageApi.joinVillage(village.value.id, {
      subjectType: 'USER',
      subjectId: currentUserId.value,
    })
    showSuccess(t('village.success.joined'))
    await Promise.all([loadVillage(), loadMembers()])
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
}

function handleRequestJoin() {
  // 承認制村: 別画面へ案内
  if (!village.value) return
  void navigateTo(`/villages/${village.value.id}/join-request`)
}

async function handleLeave() {
  if (!village.value || currentUserId.value == null) return
  // 自分の membership を探す（USER のみ対象）
  const self = members.value.find(
    m => m.subjectType === 'USER' && m.subjectId === currentUserId.value,
  )
  if (!self) {
    showError(t('village.error.VILLAGE_007'))
    return
  }
  try {
    await villageApi.leaveVillage(village.value.id, self.id)
    showSuccess(t('village.success.left'))
    await Promise.all([loadVillage(), loadMembers()])
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
}

async function handlePin() {
  if (!village.value) return
  try {
    await villageApi.addPin(village.value.id)
    showSuccess(t('village.success.pinned'))
    await loadVillage()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
}

async function handleUnpin() {
  if (!village.value) return
  try {
    await villageApi.removePin(village.value.id)
    showSuccess(t('village.success.unpinned'))
    await loadVillage()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
}

/** 村本体編集ダイアログ表示状態 — VillageEditDialog (FE α2 で新規実装) */
const showVillageEditDialog = ref(false)
function handleEdit() {
  if (!village.value) return
  showVillageEditDialog.value = true
}

/** 編集 Dialog から更新成功時に村情報を差し替え */
function onVillageUpdated(updated: VillageResponse) {
  village.value = updated
}

// =============================================================================
// 初期化
// =============================================================================

onMounted(async () => {
  await loadVillage()
  await loadMembers()
})
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <VillageHeader
      v-if="village"
      :village="village"
      active-tab="members"
      @join="handleJoin"
      @request-join="handleRequestJoin"
      @leave="handleLeave"
      @pin="handlePin"
      @unpin="handleUnpin"
      @report-click="openVillageReportDialog"
      @edit="handleEdit"
    />

    <!-- メンバー一覧本体 -->
    <VillageMembersTable
      :members="members"
      :loading="membersLoading || villageLoading"
      :village="village"
      :is-headman="isHeadman"
      :current-user-id="currentUserId"
      :total-elements="totalElements"
      :page="page"
      :page-size="PAGE_SIZE"
      @page-change="onPageChange"
      @open-role-dialog="openRoleDialog"
      @open-ban-dialog="openBanDialog"
      @open-member-report="openMemberReportDialog"
    />

    <!-- ロール変更 Dialog -->
    <VillageMembersRoleDialog
      v-model:visible="roleDialogVisible"
      v-model:new-role="roleDialogNewRole"
      :target="roleDialogTarget"
      :role-options="roleOptions"
      :submitting="roleSubmitting"
      @submit="submitRoleChange"
      @cancel="closeRoleDialog"
    />

    <!-- BAN 確認 Dialog -->
    <VillageMembersBanDialog
      v-model:visible="banDialogVisible"
      v-model:reason="banDialogReason"
      :target="banDialogTarget"
      :reason-error="banReasonError"
      :reason-max="BAN_REASON_MAX"
      :can-submit="canSubmitBan"
      :submitting="banSubmitting"
      @submit="submitBan"
      @cancel="closeBanDialog"
    />

    <!-- ============================================================== -->
    <!-- 通報 Dialog（FE5 共通コンポ）                                     -->
    <!-- ============================================================== -->
    <VillageReportDialog
      v-if="village"
      v-model:visible="reportDialogVisible"
      :village-id="village.id"
      :target-type="reportTargetType"
      :target-ref-id="reportTargetRefId"
    />

    <!-- ============================================================== -->
    <!-- 村本体編集 Dialog（FE α2 共通コンポ・HEADMAN のみ）             -->
    <!-- ============================================================== -->
    <VillageEditDialog
      v-if="village"
      v-model:visible="showVillageEditDialog"
      :village="village"
      @updated="onVillageUpdated"
    />
  </div>
</template>
