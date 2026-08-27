<script setup lang="ts">
/**
 * CMP-051 チーム / 組織の「オーナー譲渡」導線。
 *
 * # 背景
 *  BE は `POST /api/v1/{teams|organizations}/{slug}/transfer-ownership?targetUserId={id}` を
 *  実装済みだが、FE 側に画面導線が一切存在せず composable が死んでいた（CMP-051）。
 *  本コンポーネントがチーム/組織双方の唯一の導線となる。
 *
 * # 設計
 *  - チーム / 組織で API 形状が対称なため、`scopeType` で 1 コンポーネントに集約する
 *    （MemberTable と同じ流儀）。
 *  - オーナー譲渡は取り消しにくい重い操作のため、確認ダイアログで
 *    **スコープ名の完全一致入力**を要求する（メンバー除外より強い確認強度）。
 *  - 譲渡が成功すると自分は ADMIN でなくなる。権限依存 UI が古い権限のまま
 *    残らないよう `transferred` を emit し、親（永続シェル）に権限の再解決を促す。
 *  - エラーは握りつぶさず `useErrorHandler().handleApiError` に渡す。BE の
 *    message をそのままトーストに出すため、CMP-050 で追加される
 *    「譲渡先が凍結ユーザー（ROLE_001）」もユーザーに見える（専用分岐は持たない）。
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
  /** 譲渡成功。親は権限・スコープ情報を再取得すること。 */
  transferred: []
}>()

const { t } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
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
      await teamApi.transferOwnership(props.scopeSlug, targetUserId.value)
    }
    else {
      await organizationApi.transferOwnership(props.scopeSlug, targetUserId.value)
    }
    notification.success(t('transferOwnership.successTitle'), t('transferOwnership.successDetail'))
    dialogVisible.value = false
    emit('transferred')
  }
  catch (err) {
    handleApiError(err, 'TransferOwnershipPanel.submit')
  }
  finally {
    submitting.value = false
  }
}
// 注: defineExpose は行わない。expose プロキシは読み取り専用となり、ユニットテストから
// 内部 ref を書き換えられなくなるため（ExtendExpiryDialog.spec.ts と同じ流儀）。
</script>

<template>
  <SectionCard :title="t('transferOwnership.sectionTitle')">
    <p class="text-sm text-surface-600 dark:text-surface-300">
      {{ t('transferOwnership.sectionDescription') }}
    </p>
    <div class="mt-4">
      <Button
        data-testid="transfer-ownership-open"
        :label="t('transferOwnership.openButton')"
        icon="pi pi-user-edit"
        severity="danger"
        outlined
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
