<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  canChangeRole: boolean
  canRemove: boolean
  /**
   * オーナー委譲の打診ボタンを表示するか（F01.2 承諾型化）。
   * 現 ADMIN のみに表示する想定（呼び出し元が roleName === 'ADMIN' を渡す）。
   */
  canTransferOwnership?: boolean
}>()

const emit = defineEmits<{
  roleChanged: []
  memberRemoved: []
}>()

interface Member {
  userId: number
  displayName: string
  avatarUrl: string | null
  roleName: string
  joinedAt: string
}

interface PagedMembers {
  data: Member[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

/** ADMIN 側で「打診中」の可視化に使う最小限の情報（BE に一覧/詳細取得 API が無いため localStorage で保持）。 */
interface PendingOfferInfo {
  offerId: string
  targetUserId: number
  targetDisplayName: string
  expiresAt: string | undefined
}

const api = useApi()
const notification = useNotification()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const { confirmAction } = useConfirmDialog()
const authStore = useAuthStore()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const { formatDate } = useDatetime()
const members = ref<Member[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const page = ref(0)
const rows = ref(20)
const transferringUserId = ref<number | null>(null)
const pendingOffer = ref<PendingOfferInfo | null>(null)
const cancellingOffer = ref(false)

const currentUserId = computed(() => authStore.user?.id ?? null)
const showActionsColumn = computed(
  () => props.canChangeRole || props.canRemove || props.canTransferOwnership,
)

/** localStorage キー（オファーの一覧/詳細取得 API が BE に無いため、ADMIN 端末のローカル状態で「打診中」を可視化する）。 */
function pendingOfferStorageKey(): string {
  return `ownership-transfer-offer:${props.scopeType}:${props.scopeId}`
}

function loadPendingOfferFromStorage() {
  if (!import.meta.client) return
  try {
    const raw = localStorage.getItem(pendingOfferStorageKey())
    pendingOffer.value = raw ? (JSON.parse(raw) as PendingOfferInfo) : null
  }
  catch {
    pendingOffer.value = null
  }
}

function savePendingOfferToStorage(info: PendingOfferInfo | null) {
  if (!import.meta.client) return
  const key = pendingOfferStorageKey()
  if (info) {
    localStorage.setItem(key, JSON.stringify(info))
  }
  else {
    localStorage.removeItem(key)
  }
}

async function loadMembers() {
  loading.value = true
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    const response = await api<PagedMembers>(
      `/api/v1/${base}/${props.scopeId}/members?page=${page.value}&size=${rows.value}`
    )
    members.value = response.data
    totalRecords.value = response.meta.totalElements
  }
  catch (error) {
    // 握りつぶし禁止（障害対応の原則）: 読み込み失敗はエラー通知で表面化する。
    // 既存表示を勝手に空へ倒すと「メンバー0件」の誤解を招くため clobber しない。
    handleApiError(error, 'loadMembers')
  }
  finally {
    loading.value = false
  }
}

async function onChangeRole(userId: number, roleId: number) {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
    notification.success(t('member.roleChanged'))
    await loadMembers()
    emit('roleChanged')
  }
  catch {
    notification.error(t('member.roleChangeFailed'))
  }
}

/** メンバー除外（危険操作）。ネイティブ confirm ではなく共通の確認ダイアログに統一（オファー系と同作法）。 */
function onRemoveMember(member: Member) {
  confirmAction({
    header: t('member.remove.confirmTitle'),
    message: t('member.remove.confirmMessage', { name: member.displayName }),
    onAccept: () => doRemoveMember(member),
  })
}

async function doRemoveMember(member: Member) {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${member.userId}`, { method: 'DELETE' })
    notification.success(t('member.removed'))
    await loadMembers()
    emit('memberRemoved')
  }
  catch {
    notification.error(t('member.removeFailed'))
  }
}

/**
 * オーナー委譲を打診する（F01.2 承諾型化）。
 * 危険操作として赤ボタン既定の確認ダイアログを経由し、押した瞬間には委譲されず
 * 「承諾型オファー（PENDING）」を作成するだけであることを明示する。
 */
function onTransferOwnership(member: Member) {
  confirmAction({
    header: t('role.transfer.offer.confirmTitle'),
    message: t('role.transfer.offer.confirmMessage', { name: member.displayName }),
    onAccept: () => doCreateOffer(member),
  })
}

async function doCreateOffer(member: Member) {
  transferringUserId.value = member.userId
  try {
    const createOffer = props.scopeType === 'team' ? teamApi.createOwnershipOffer : orgApi.createOwnershipOffer
    const res = await createOffer(props.scopeId, member.userId)
    const offer = res.data
    if (!offer?.offerId) {
      notification.error(t('error.unknown'))
      return
    }
    const info: PendingOfferInfo = {
      offerId: offer.offerId,
      targetUserId: member.userId,
      targetDisplayName: member.displayName,
      expiresAt: offer.expiresAt,
    }
    pendingOffer.value = info
    savePendingOfferToStorage(info)
    notification.success(t('role.transfer.offer.created'))
  }
  catch (error) {
    handleApiError(error, 'createOwnershipOffer')
  }
  finally {
    transferringUserId.value = null
  }
}

/** 打診中オファーの取消（発行者＝現 ADMIN のみ）。 */
function onCancelOffer() {
  if (!pendingOffer.value) return
  confirmAction({
    header: t('role.transfer.offer.cancelConfirmTitle'),
    message: t('role.transfer.offer.cancelConfirmMessage'),
    onAccept: doCancelOffer,
  })
}

async function doCancelOffer() {
  if (!pendingOffer.value) return
  cancellingOffer.value = true
  try {
    const cancelOffer = props.scopeType === 'team' ? teamApi.cancelOwnershipOffer : orgApi.cancelOwnershipOffer
    await cancelOffer(props.scopeId, pendingOffer.value.offerId)
    notification.success(t('role.transfer.offer.cancelSuccess'))
    pendingOffer.value = null
    savePendingOfferToStorage(null)
  }
  catch (error) {
    handleApiError(error, 'cancelOwnershipOffer')
  }
  finally {
    cancellingOffer.value = false
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadMembers()
}

function formatMemberDate(dateStr: string): string {
  return formatDate(dateStr)
}

onMounted(() => {
  loadMembers()
  loadPendingOfferFromStorage()
})

defineExpose({ refresh: loadMembers, changeRole: onChangeRole })
</script>

<template>
  <div>
    <!-- 打診中オファーの可視化（ADMIN 側・発行者のみ。BE に一覧/詳細取得 API が無いため
         このブラウザで打診した直近のオファーのみ表示できる制約がある） -->
    <div
      v-if="canTransferOwnership && pendingOffer"
      class="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm dark:border-amber-800 dark:bg-amber-950"
    >
      <span>
        {{ t('role.transfer.offer.pendingByMe', { name: pendingOffer.targetDisplayName }) }}
      </span>
      <Button
        :label="t('role.transfer.offer.cancel')"
        severity="secondary"
        size="small"
        text
        :loading="cancellingOffer"
        @click="onCancelOffer"
      />
    </div>

    <DataTable
      :value="members"
      :loading="loading"
      lazy
      paginator
      :rows="rows"
      :total-records="totalRecords"
      :rows-per-page-options="[10, 20, 50]"
      data-key="userId"
      @page="onPage"
    >
      <Column :header="t('member.columns.name')" field="displayName">
        <template #body="{ data }">
          <div class="flex items-center gap-3">
            <Avatar
              :image="data.avatarUrl"
              :label="data.avatarUrl ? undefined : data.displayName?.charAt(0)"
              shape="circle"
              size="normal"
            />
            <span class="font-medium">{{ data.displayName }}</span>
          </div>
        </template>
      </Column>
      <Column :header="t('member.columns.role')" field="roleName" style="width: 160px">
        <template #body="{ data }">
          <RoleBadge :role="data.roleName" />
        </template>
      </Column>
      <Column :header="t('member.columns.joinedAt')" field="joinedAt" style="width: 120px">
        <template #body="{ data }">
          {{ formatMemberDate(data.joinedAt) }}
        </template>
      </Column>
      <Column v-if="showActionsColumn" :header="t('member.columns.actions')" style="width: 140px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="canTransferOwnership && data.userId !== currentUserId"
              icon="pi pi-arrow-right-arrow-left"
              severity="secondary"
              text
              rounded
              size="small"
              :loading="transferringUserId === data.userId"
              :title="t('role.transfer.offer.button')"
              :aria-label="t('role.transfer.offer.button')"
              @click="onTransferOwnership(data)"
            />
            <Button
              v-if="canRemove"
              icon="pi pi-trash"
              severity="danger"
              text
              rounded
              size="small"
              @click="onRemoveMember(data)"
            />
          </div>
        </template>
      </Column>
      <template #empty>
        <div class="p-4 text-center text-surface-500">
          {{ t('member.empty') }}
        </div>
      </template>
    </DataTable>
  </div>
</template>
