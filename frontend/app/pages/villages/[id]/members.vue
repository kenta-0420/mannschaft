<script setup lang="ts">
/**
 * F17.1 村機能 — メンバー一覧ページ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.3 / §4.11
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・join/leave/pin 等のアクションは
 * 親 `pages/villages/[id].vue` に集約。本ファイルはメンバー一覧パネル本体（一覧取得・
 * ロール変更・BAN・メンバー通報）のみを担う。
 *
 * 画面構成:
 *   1. メンバー一覧テーブル（VillageMembersTable 子コンポ）
 *   2. ロール変更 Dialog（VillageMembersRoleDialog 子コンポ・HEADMAN のみ）
 *   3. BAN 確認 Dialog（VillageMembersBanDialog 子コンポ・HEADMAN のみ）
 *   4. 通報 Dialog（VillageReportDialog 共通コンポ・MEMBERSHIP 対象）
 *
 * 権限:
 *   - 自分が HEADMAN（村長）の場合のみ「ロール変更」「BAN」操作ボタンを表示。
 *   - HEADMAN 判定は親コンテキスト perms を主とし、members 配列をフォールバックに使う。
 */
import VillageReportDialog from '~/components/VillageReportDialog.vue'
import VillageMemberProfileDialog from '~/components/villages/members/VillageMemberProfileDialog.vue'
import VillageMembersBanDialog from '~/components/villages/members/VillageMembersBanDialog.vue'
import VillageMembersRoleDialog from '~/components/villages/members/VillageMembersRoleDialog.vue'
import VillageMembersTable from '~/components/villages/members/VillageMembersTable.vue'
import type {
  MembershipResponse,
  VillageReportTargetType,
  VillageRole,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError, showWarn } = useNotification()

const villageId = computed<string>(() => String(route.params.id))

// 村本体・権限・ユーザー id は親シェルから inject
const { village, perms, currentUserId } = useVillageContext()

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

// Dialog 状態 — 村人ミニプロフィール（F17.2 Wave3 ⑥・§9.5）
const memberProfileDialogVisible = ref(false)
const memberProfileTarget = ref<MembershipResponse | null>(null)

// =============================================================================
// 派生値
// =============================================================================

/**
 * 自分が HEADMAN かどうか。
 *
 * 判定方針:
 *   1. 親コンテキストの perms.isHeadman（VillageResponse.myRole 由来）を主に採用
 *   2. それが false の場合は members 配列から自分の USER membership をフォールバック確認
 */
const isHeadman = computed<boolean>(() => {
  if (perms.value.isHeadman) return true
  if (currentUserId.value == null) return false
  return members.value.some(
    m => m.subjectType === 'USER' && m.subjectId === currentUserId.value && m.role === 'HEADMAN' && !m.isBanned,
  )
})

// =============================================================================
// 表示ヘルパ（自身の判定など）
// =============================================================================

/** 自分自身の membership 行かどうか（自分への操作は禁止） */
function isSelfMembership(m: MembershipResponse): boolean {
  if (currentUserId.value == null) return false
  return m.subjectType === 'USER' && m.subjectId === currentUserId.value
}

/**
 * 自分以外に役職変更できる生存メンバー数。
 * BAN 済みは操作対象外なので除外する（自分自身も除外）。
 */
const otherActiveMemberCount = computed<number>(() =>
  members.value.filter(m => !isSelfMembership(m) && !m.isBanned).length,
)

/**
 * 「ソロ村（村長ひとり）」の空状態案内を出すか。
 *
 * 村長ひとりしか居ない村では役職変更操作が一切出せないため、
 * 「壊れている」と誤解されやすい（実機で誤解が発生）。HEADMAN のときのみ
 * 「他のメンバーが参加すれば長老に任命できる」旨を案内する。
 * 村長でない者（長老・村人）には出さない（そもそも役職変更できないため）。
 */
const showSoloHeadmanHint = computed<boolean>(() =>
  isHeadman.value && !membersLoading.value && otherActiveMemberCount.value === 0,
)

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
// 通報（メンバー対象）
// =============================================================================

function openMemberReportDialog(m: MembershipResponse) {
  reportTargetType.value = 'MEMBERSHIP'
  reportTargetRefId.value = m.id
  reportDialogVisible.value = true
}

// =============================================================================
// 村人ミニプロフィール（F17.2 Wave3 ⑥・§9.5）
// =============================================================================

function openMemberProfileDialog(m: MembershipResponse) {
  memberProfileTarget.value = m
  memberProfileDialogVisible.value = true
}

// =============================================================================
// 初期化
// =============================================================================

onMounted(() => {
  void loadMembers()
})
</script>

<template>
  <div>
    <!-- メンバー一覧本体 -->
    <VillageMembersTable
      :members="members"
      :loading="membersLoading"
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
      @open-member-profile="openMemberProfileDialog"
    />

    <!-- ソロ村（村長ひとり）の空状態案内。
         他に役職変更できる生存メンバーが居ない HEADMAN のときのみ表示（誤解防止）。 -->
    <DashboardEmptyState
      v-if="showSoloHeadmanHint"
      icon="pi pi-users"
      :message="t('village.members.soloHeadmanHint')"
      data-testid="village-members-solo-headman-hint"
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

    <!-- 通報 Dialog（メンバー対象） -->
    <VillageReportDialog
      v-if="village"
      v-model:visible="reportDialogVisible"
      :village-id="village.id"
      :target-type="reportTargetType"
      :target-ref-id="reportTargetRefId"
    />

    <!-- 村人ミニプロフィール Dialog（所属村一覧・自分自身なら公開トグル・F17.2 Wave3 ⑥） -->
    <VillageMemberProfileDialog
      v-model:visible="memberProfileDialogVisible"
      :village-id="villageId"
      :member="memberProfileTarget"
    />
  </div>
</template>
