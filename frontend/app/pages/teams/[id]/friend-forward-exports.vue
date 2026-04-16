<script setup lang="ts">
import type { FriendForwardExportView } from '~/types/social-friend'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const friendFeedApi = useFriendFeedApi()
const notification = useNotification()
const { isAdmin, can, loadPermissions, loading: roleLoading } = useRoleAccess('team', teamId)

const canManage = computed(() => isAdmin.value || can('MANAGE_FRIEND_TEAMS'))

// ─────────────────────────────────────────
// 転送エクスポート履歴一覧
// ─────────────────────────────────────────
const exports = ref<FriendForwardExportView[]>([])
const listLoading = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const PAGE_SIZE = 20

async function loadExports(page = 0) {
  listLoading.value = true
  try {
    const res = await friendFeedApi.listExportedPosts(teamId.value, { page, size: PAGE_SIZE })
    exports.value = res.data
    totalRecords.value = res.pagination.totalElements
    currentPage.value = page
  }
  catch (error) {
    friendFeedApi.handleApiError(error, '転送エクスポート履歴取得')
  }
  finally {
    listLoading.value = false
  }
}

function onPage(event: { page: number }) {
  loadExports(event.page)
}

// ─────────────────────────────────────────
// 転送取消
// ─────────────────────────────────────────
const showRevokeDialog = ref(false)
const revokeTarget = ref<FriendForwardExportView | null>(null)
const revokeSubmitting = ref(false)

function openRevokeDialog(item: FriendForwardExportView) {
  revokeTarget.value = item
  showRevokeDialog.value = true
}

async function submitRevoke() {
  if (!revokeTarget.value) return
  revokeSubmitting.value = true
  try {
    await friendFeedApi.revokeForward(teamId.value, revokeTarget.value.forwardId)
    notification.success(t('team.forwardExports.revokeSuccess'))
    showRevokeDialog.value = false
    revokeTarget.value = null
    await loadExports(currentPage.value)
  }
  catch (error) {
    friendFeedApi.handleApiError(error, '転送取消')
  }
  finally {
    revokeSubmitting.value = false
  }
}

// ─────────────────────────────────────────
// ユーティリティ
// ─────────────────────────────────────────
function formatDate(iso: string) {
  return new Date(iso).toLocaleString('ja-JP')
}

function targetLabel(target: FriendForwardExportView['target']) {
  return target === 'MEMBER'
    ? t('team.forwardExports.targetMember')
    : t('team.forwardExports.targetMemberAndSupporter')
}

// ─────────────────────────────────────────
// 初期化
// ─────────────────────────────────────────
onMounted(async () => {
  await loadPermissions()
  if (canManage.value) {
    await loadExports(0)
  }
})

watch(canManage, (val) => {
  if (val && exports.value.length === 0 && !listLoading.value) {
    loadExports(0)
  }
})
</script>

<template>
  <div>
    <div class="mb-6">
      <PageHeader :title="t('team.forwardExports.title')" />
    </div>

    <!-- 権限なし -->
    <div v-if="!roleLoading && !canManage" class="py-16 text-center text-surface-500">
      <i class="pi pi-lock mb-4 text-4xl" />
      <p>{{ t('error.COMMON_002') }}</p>
    </div>

    <!-- ローディング -->
    <div v-else-if="roleLoading || (listLoading && exports.length === 0)" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <!-- 一覧 -->
    <template v-else-if="canManage">
      <!-- 空状態 -->
      <div v-if="exports.length === 0 && !listLoading" class="py-16 text-center text-surface-500">
        <i class="pi pi-send mb-4 text-4xl" />
        <p>{{ t('team.forwardExports.empty') }}</p>
      </div>

      <!-- テーブル -->
      <DataTable
        v-else
        :value="exports"
        :loading="listLoading"
        :rows="PAGE_SIZE"
        :total-records="totalRecords"
        lazy
        paginator
        @page="onPage"
      >
        <!-- 転送チーム -->
        <Column :header="t('team.forwardExports.fromTeam')" field="forwardingTeamName" />

        <!-- 配信範囲 -->
        <Column :header="t('team.forwardExports.target')">
          <template #body="{ data }: { data: FriendForwardExportView }">
            {{ targetLabel(data.target) }}
          </template>
        </Column>

        <!-- コメント -->
        <Column :header="t('team.forwardExports.comment')" field="comment">
          <template #body="{ data }: { data: FriendForwardExportView }">
            <span class="text-surface-500">{{ data.comment ?? '—' }}</span>
          </template>
        </Column>

        <!-- 転送日時 -->
        <Column :header="t('team.forwardExports.forwardedAt')">
          <template #body="{ data }: { data: FriendForwardExportView }">
            {{ formatDate(data.forwardedAt) }}
          </template>
        </Column>

        <!-- ステータス -->
        <Column :header="t('team.forwardExports.revoked')">
          <template #body="{ data }: { data: FriendForwardExportView }">
            <Tag
              v-if="data.isRevoked"
              :value="t('team.forwardExports.revoked')"
              severity="secondary"
              icon="pi pi-ban"
            />
            <Tag
              v-else
              :value="t('team.forwardExports.active')"
              severity="success"
              icon="pi pi-check"
            />
          </template>
        </Column>

        <!-- 操作 -->
        <Column :header="t('label.actions')">
          <template #body="{ data }: { data: FriendForwardExportView }">
            <Button
              v-if="!data.isRevoked"
              :label="t('team.forwardExports.revoke')"
              severity="danger"
              size="small"
              outlined
              @click="openRevokeDialog(data)"
            />
          </template>
        </Column>
      </DataTable>
    </template>

    <!-- 転送取消確認ダイアログ -->
    <Dialog
      v-model:visible="showRevokeDialog"
      :header="t('team.forwardExports.revoke')"
      :style="{ width: '400px' }"
      modal
    >
      <p>{{ t('team.forwardExports.revokeConfirm') }}</p>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showRevokeDialog = false"
        />
        <Button
          :label="t('team.forwardExports.revoke')"
          severity="danger"
          :loading="revokeSubmitting"
          @click="submitRevoke"
        />
      </template>
    </Dialog>
  </div>
</template>
