<script setup lang="ts">
/**
 * CMP-051 / F04.12 チーム・組織のオーナー委譲打診導線。
 *
 * # 設計
 *  - チーム / 組織で API 形状が対称なため、`scopeType` で 1 コンポーネントに集約する
 *    （MemberTable と同じ流儀）。
 *  - 委譲打診は将来の権限変更につながる重い操作のため、確認ダイアログで
 *    **スコープ名の完全一致入力**を要求する（メンバー除外より強い確認強度）。
 *  - この操作では権限を即時変更せず、対象者が承諾した時点で委譲する。
 *    打診作成後は `offered` を emit し、親に保留状態の再取得を促す。
 *  - エラーは握りつぶさず `useErrorHandler().handleApiError` に渡す。BE の
 *    message をそのままトーストに出すため、CMP-050 で追加される
 *    「打診先が凍結ユーザー（ROLE_001）」もユーザーに見える（専用分岐は持たない）。
 */
import type { MemberResponse } from '~/types/member'

const props = defineProps<{
  /** 対象スコープ種別。 */
  scopeType: 'team' | 'organization'
  /** 対象スコープの slug（URL 識別子）。 */
  scopeSlug: string
  /** 確認入力で突き合わせる表示名。 */
  scopeName: string
}>()

const emit = defineEmits<{
  /** 委譲の打診作成成功。親は表示状態を再取得すること。 */
  offered: []
}>()

const { t } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { confirmAction } = useConfirmDialog()
const teamApi = useTeamApi()
const organizationApi = useOrganizationApi()
const authStore = useAuthStore()

/** 譲渡先候補の取得上限（メンバー一覧は Select で一括表示する）。 */
const MEMBER_FETCH_SIZE = 200

const dialogVisible = ref(false)
const members = ref<MemberResponse[]>([])
const loadingMembers = ref(false)
const targetUserId = ref<number | null>(null)
const confirmationName = ref('')
const submitting = ref(false)
type PendingOffer = {
  offerId: string
  target: { userId: number, displayName?: string | null }
  expiresAt?: string
}
const pendingOffer = ref<PendingOffer | null>(null)
const loadingPending = ref(false)
const cancellingOffer = ref(false)

const pendingTargetLabel = computed(() => {
  if (!pendingOffer.value) return ''
  return pendingOffer.value.target.displayName
    ?? members.value.find(member => member.userId === pendingOffer.value?.target.userId)?.displayName
    ?? `#${pendingOffer.value.target.userId}`
})

/** 自分自身は譲渡先になり得ないため候補から除く。 */
const candidates = computed<MemberResponse[]>(() => {
  const myId = authStore.user?.id
  return members.value.filter((m) => m.userId !== myId)
})

/** 確認入力がスコープ名と完全一致しているか（前後の空白のみ許容）。 */
const confirmationMatched = computed<boolean>(
  () => confirmationName.value.trim() === props.scopeName.trim() && props.scopeName.trim() !== '',
)

const canSubmit = computed<boolean>(
  () => !submitting.value && targetUserId.value !== null && confirmationMatched.value,
)

async function loadMembers(): Promise<void> {
  loadingMembers.value = true
  try {
    const response
      = props.scopeType === 'team'
        ? await teamApi.getMembers(props.scopeSlug, { page: 0, size: MEMBER_FETCH_SIZE })
        : await organizationApi.getMembers(props.scopeSlug, { page: 0, size: MEMBER_FETCH_SIZE })
    members.value = response.data
  }
  catch (err) {
    // 候補が取れないことはユーザーに見せる（黙って空リストにしない）。
    members.value = []
    handleApiError(err, 'TransferOwnershipPanel.loadMembers')
  }
  finally {
    loadingMembers.value = false
  }
}

async function loadPendingOffer(): Promise<void> {
  loadingPending.value = true
  try {
    const response = props.scopeType === 'team'
      ? await teamApi.getPendingOwnershipOffers(props.scopeSlug)
      : await organizationApi.getPendingOwnershipOffers(props.scopeSlug)
    pendingOffer.value = (response.data[0] as PendingOffer | undefined) ?? null
    if (pendingOffer.value && members.value.length === 0) await loadMembers()
  }
  catch (err) {
    pendingOffer.value = null
    handleApiError(err, 'TransferOwnershipPanel.loadPendingOffer')
  }
  finally {
    loadingPending.value = false
  }
}

async function openDialog(): Promise<void> {
  targetUserId.value = null
  confirmationName.value = ''
  dialogVisible.value = true
  await loadMembers()
}

function closeDialog(): void {
  dialogVisible.value = false
}

async function submit(): Promise<void> {
  // 確認入力が一致していない / 譲渡先未選択のあいだは API を呼ばない。
  if (!canSubmit.value || targetUserId.value === null) return
  submitting.value = true
  try {
    if (props.scopeType === 'team') {
      const response = await teamApi.createOwnershipOffer(props.scopeSlug, targetUserId.value)
      pendingOffer.value = response.data as PendingOffer
    }
    else {
      const response = await organizationApi.createOwnershipOffer(props.scopeSlug, targetUserId.value)
      pendingOffer.value = response.data as PendingOffer
    }
    notification.success(t('role.transfer.offer.created'))
    dialogVisible.value = false
    emit('offered')
  }
  catch (err) {
    handleApiError(err, 'TransferOwnershipPanel.submit')
  }
  finally {
    submitting.value = false
  }
}


function onCancelOffer(): void {
  if (!pendingOffer.value) return
  confirmAction({
    header: t('role.transfer.offer.cancelConfirmTitle'),
    message: t('role.transfer.offer.cancelConfirmMessage'),
    onAccept: cancelOffer,
  })
}

async function cancelOffer(): Promise<void> {
  if (!pendingOffer.value) return
  cancellingOffer.value = true
  try {
    if (props.scopeType === 'team') {
      await teamApi.cancelOwnershipOffer(props.scopeSlug, pendingOffer.value.offerId)
    }
    else {
      await organizationApi.cancelOwnershipOffer(props.scopeSlug, pendingOffer.value.offerId)
    }
    pendingOffer.value = null
    notification.success(t('role.transfer.offer.cancelSuccess'))
  }
  catch (err) {
    handleApiError(err, 'TransferOwnershipPanel.cancelOffer')
  }
  finally {
    cancellingOffer.value = false
  }
}

onMounted(loadPendingOffer)
// 注: defineExpose は行わない。expose プロキシは読み取り専用となり、ユニットテストから
// 内部 ref を書き換えられなくなるため（ExtendExpiryDialog.spec.ts と同じ流儀）。
</script>

<template>
  <SectionCard :title="t('transferOwnership.sectionTitle')">
    <p class="text-sm text-surface-600 dark:text-surface-300">
      {{ t('transferOwnership.sectionDescription') }}
    </p>
    <Message
      v-if="pendingOffer"
      severity="info"
      :closable="false"
      class="mt-4"
      data-testid="transfer-ownership-pending"
    >
      <div class="flex flex-wrap items-center justify-between gap-3">
        <span>{{ t('role.transfer.offer.pendingByMe', { name: pendingTargetLabel }) }}</span>
        <Button
          data-testid="transfer-ownership-cancel-pending"
          :label="t('role.transfer.offer.cancel')"
          severity="danger"
          text
          size="small"
          :loading="cancellingOffer"
          @click="onCancelOffer"
        />
      </div>
    </Message>
    <div class="mt-4">
      <Button
        data-testid="transfer-ownership-open"
        :label="t('transferOwnership.openButton')"
        icon="pi pi-user-edit"
        severity="danger"
        outlined
        :disabled="loadingPending || pendingOffer !== null"
        @click="openDialog"
      />
    </div>

    <Dialog
      v-model:visible="dialogVisible"
      modal
      :closable="!submitting"
      :draggable="false"
      :header="t('transferOwnership.dialogTitle')"
      :style="{ width: '34rem' }"
      :breakpoints="{ '960px': '75vw', '640px': '95vw' }"
      :pt="{ root: { 'data-testid': 'transfer-ownership-dialog' } }"
    >
      <div class="flex flex-col gap-4 py-2">
        <Message severity="warn" :closable="false">
          {{ t('transferOwnership.warning') }}
        </Message>

        <!-- 譲渡先メンバー -->
        <div>
          <label for="transfer-ownership-target" class="mb-1 block text-sm font-medium">
            {{ t('transferOwnership.targetLabel') }}
            <span class="text-red-600">*</span>
          </label>
          <Select
            id="transfer-ownership-target"
            v-model="targetUserId"
            data-testid="transfer-ownership-target"
            :options="candidates"
            option-label="displayName"
            option-value="userId"
            :loading="loadingMembers"
            :disabled="submitting"
            :placeholder="t('transferOwnership.targetPlaceholder')"
            class="w-full"
          />
          <p
            v-if="!loadingMembers && candidates.length === 0"
            class="mt-1 text-xs text-surface-500"
          >
            {{ t('transferOwnership.noCandidates') }}
          </p>
        </div>

        <!-- 確認入力（スコープ名の完全一致） -->
        <div>
          <label for="transfer-ownership-confirm" class="mb-1 block text-sm font-medium">
            {{ t('transferOwnership.confirmLabel', { name: scopeName }) }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            id="transfer-ownership-confirm"
            v-model="confirmationName"
            data-testid="transfer-ownership-confirm"
            class="w-full text-base"
            autocomplete="off"
            :disabled="submitting"
            :placeholder="scopeName"
          />
          <p
            v-if="confirmationName !== '' && !confirmationMatched"
            class="mt-1 text-xs text-red-600"
          >
            {{ t('transferOwnership.confirmMismatch') }}
          </p>
        </div>
      </div>

      <template #footer>
        <Button
          data-testid="transfer-ownership-cancel"
          :label="t('transferOwnership.cancel')"
          severity="secondary"
          text
          :disabled="submitting"
          @click="closeDialog"
        />
        <Button
          data-testid="transfer-ownership-submit"
          :label="t('transferOwnership.submit')"
          icon="pi pi-check"
          severity="danger"
          :disabled="!canSubmit"
          :loading="submitting"
          @click="submit"
        />
      </template>
    </Dialog>
  </SectionCard>
</template>
