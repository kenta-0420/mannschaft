<script setup lang="ts">
/**
 * 柱②-2: 販促プロビジョニング SYSTEM_ADMIN 向け管理画面。
 *
 * `pages/system-admin/incident-banners/index.vue` を金型に踏襲（一覧 + Dialog CRUD）。
 * - 組織 / チームの PROVISIONED 事前作成フォーム（名称・招待メール）
 * - 招待一覧（状態・有効期限・発行者）
 * - 招待の再送・取消操作
 *
 * API: /api/v1/system-admin/provisioning/** (ROLE_SYSTEM_ADMIN)
 */
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'

import type { ProvisioningInvitationResponse } from '~/composables/useProvisioningAdminApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const authStore = useAuthStore()
const provisioningApi = useProvisioningAdminApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { formatDateTime } = useDatetime()

// SYSTEM_ADMIN 権限チェック
const isAllowed = computed(() => authStore.isSystemAdmin)

// =============================================================================
// 招待一覧
// =============================================================================

const invitations = ref<ProvisioningInvitationResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    invitations.value = await provisioningApi.list()
  } catch (err) {
    console.error('system-admin/provisioning/index.vue: load failed', err)
    notification.error(t('provisioning.admin.loadFailed'))
    invitations.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 対象種別ラベル（team/organization どちらのIDが立っているかで判定）。 */
function targetLabel(row: ProvisioningInvitationResponse): string {
  if (row.teamId != null) return t('provisioning.admin.targetTeam')
  if (row.organizationId != null) return t('provisioning.admin.targetOrganization')
  return '-'
}

function statusLabel(status?: string): string {
  if (!status) return '-'
  const key = `provisioning.admin.status.${status}`
  return t(key)
}

function statusSeverity(status?: string): 'success' | 'warn' | 'secondary' | 'danger' {
  if (status === 'ACCEPTED') return 'success'
  if (status === 'PENDING') return 'warn'
  if (status === 'EXPIRED') return 'danger'
  return 'secondary'
}

// =============================================================================
// 作成 Dialog（組織 / チーム共通）
// =============================================================================

const dialogOpen = ref(false)
const dialogKind = ref<'organization' | 'team'>('organization')
const saving = ref(false)

const formName = ref('')
const formInviteEmail = ref('')

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const nameError = computed<string | null>(() => {
  if (!formName.value.trim()) return t('provisioning.admin.form.nameRequired')
  return null
})

const emailError = computed<string | null>(() => {
  const v = formInviteEmail.value.trim()
  if (!v) return t('provisioning.admin.form.emailRequired')
  if (!EMAIL_PATTERN.test(v)) return t('provisioning.admin.form.emailInvalid')
  return null
})

const canSave = computed(() => !nameError.value && !emailError.value && !saving.value)

function openCreate(kind: 'organization' | 'team') {
  dialogKind.value = kind
  formName.value = ''
  formInviteEmail.value = ''
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    if (dialogKind.value === 'organization') {
      await provisioningApi.createOrganization({
        name: formName.value.trim(),
        inviteEmail: formInviteEmail.value.trim(),
      })
      notification.success(t('provisioning.admin.createOrgSuccess'))
    } else {
      await provisioningApi.createTeam({
        name: formName.value.trim(),
        inviteEmail: formInviteEmail.value.trim(),
      })
      notification.success(t('provisioning.admin.createTeamSuccess'))
    }
    closeDialog()
    await load()
  } catch (err) {
    console.error('system-admin/provisioning/index.vue: save failed', err)
    handleApiError(err, 'provisioning-create')
  } finally {
    saving.value = false
  }
}

// =============================================================================
// 再送 / 取消
// =============================================================================

const resendingId = ref<string | null>(null)
const cancellingId = ref<string | null>(null)

async function resend(row: ProvisioningInvitationResponse) {
  if (!row.id) return
  resendingId.value = row.id
  try {
    await provisioningApi.resend(row.id)
    notification.success(t('provisioning.admin.resendSuccess'))
    await load()
  } catch (err) {
    console.error('system-admin/provisioning/index.vue: resend failed', err)
    handleApiError(err, 'provisioning-resend')
  } finally {
    resendingId.value = null
  }
}

async function cancelInvite(row: ProvisioningInvitationResponse) {
  if (!row.id) return
  if (!confirm(t('provisioning.admin.cancelConfirm'))) return
  cancellingId.value = row.id
  try {
    await provisioningApi.cancel(row.id)
    notification.success(t('provisioning.admin.cancelSuccess'))
    await load()
  } catch (err) {
    console.error('system-admin/provisioning/index.vue: cancel failed', err)
    handleApiError(err, 'provisioning-cancel')
  } finally {
    cancellingId.value = null
  }
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <!-- 権限チェック -->
    <div
      v-if="!isAllowed"
      class="flex flex-col items-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400"
    >
      <i class="pi pi-lock text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('provisioning.admin.noPermission') }}</p>
    </div>

    <template v-else>
      <!-- ヘッダー -->
      <header class="flex items-center justify-between">
        <div>
          <span
            class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            {{ t('provisioning.admin.badge') }}
          </span>
          <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
            {{ t('provisioning.admin.title') }}
          </h1>
        </div>
        <div class="flex items-center gap-2">
          <Button
            v-tooltip.left="t('provisioning.admin.reloadTooltip')"
            icon="pi pi-refresh"
            text
            rounded
            :loading="loading"
            @click="load"
          />
          <Button
            :label="t('provisioning.admin.createOrgBtn')"
            icon="pi pi-building"
            severity="secondary"
            @click="openCreate('organization')"
          />
          <Button
            :label="t('provisioning.admin.createTeamBtn')"
            icon="pi pi-users"
            @click="openCreate('team')"
          />
        </div>
      </header>

      <!-- ローディング -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
      </div>

      <!-- 招待一覧テーブル -->
      <section>
        <h2 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ t('provisioning.admin.invitationsTitle') }}
        </h2>
        <DataTable
          v-if="!loading"
          :value="invitations"
          data-key="id"
          striped-rows
          class="text-sm"
        >
          <template #empty>
            <div class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400">
              <i class="pi pi-inbox text-4xl" aria-hidden="true" />
              <p class="text-sm">{{ t('provisioning.admin.noData') }}</p>
            </div>
          </template>

          <Column :header="t('provisioning.admin.table.target')" style="width: 8rem">
            <template #body="{ data: row }: { data: ProvisioningInvitationResponse }">
              <Tag :value="targetLabel(row)" severity="info" />
            </template>
          </Column>

          <Column
            :header="t('provisioning.admin.table.inviteEmail')"
            field="inviteEmail"
            style="min-width: 14rem"
          />

          <Column :header="t('provisioning.admin.table.status')" style="width: 9rem">
            <template #body="{ data: row }: { data: ProvisioningInvitationResponse }">
              <Tag :value="statusLabel(row.status)" :severity="statusSeverity(row.status)" />
            </template>
          </Column>

          <Column :header="t('provisioning.admin.table.expiresAt')" style="width: 11rem">
            <template #body="{ data: row }: { data: ProvisioningInvitationResponse }">
              <span class="text-xs text-surface-500 dark:text-surface-400">
                {{ row.expiresAt ? formatDateTime(row.expiresAt) : '-' }}
              </span>
            </template>
          </Column>

          <Column :header="t('provisioning.admin.table.issuedBy')" field="issuedBy" style="width: 8rem" />

          <Column :header="t('provisioning.admin.table.actions')" style="width: 14rem">
            <template #body="{ data: row }: { data: ProvisioningInvitationResponse }">
              <div v-if="row.status === 'PENDING'" class="flex flex-wrap items-center gap-1">
                <Button
                  :label="t('provisioning.admin.resendBtn')"
                  icon="pi pi-send"
                  size="small"
                  severity="secondary"
                  :loading="resendingId === row.id"
                  @click="resend(row)"
                />
                <Button
                  :label="t('provisioning.admin.cancelInviteBtn')"
                  icon="pi pi-times"
                  size="small"
                  severity="danger"
                  text
                  :loading="cancellingId === row.id"
                  @click="cancelInvite(row)"
                />
              </div>
              <span v-else class="text-xs text-surface-400">-</span>
            </template>
          </Column>
        </DataTable>
      </section>
    </template>

    <!-- 作成 Dialog -->
    <Dialog
      v-model:visible="dialogOpen"
      modal
      :header="dialogKind === 'organization'
        ? t('provisioning.admin.createOrgDialogTitle')
        : t('provisioning.admin.createTeamDialogTitle')"
      :style="{ width: '32rem' }"
      :draggable="false"
      @hide="closeDialog"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('provisioning.admin.form.nameLabel') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            v-model="formName"
            :placeholder="t('provisioning.admin.form.namePlaceholder')"
            :invalid="!!nameError && formName.length > 0"
            class="w-full"
            maxlength="255"
          />
          <p v-if="nameError && formName.length > 0" class="mt-1 text-xs text-red-600">
            {{ nameError }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('provisioning.admin.form.inviteEmailLabel') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            v-model="formInviteEmail"
            :placeholder="t('provisioning.admin.form.inviteEmailPlaceholder')"
            :invalid="!!emailError && formInviteEmail.length > 0"
            class="w-full"
            maxlength="255"
          />
          <p v-if="emailError && formInviteEmail.length > 0" class="mt-1 text-xs text-red-600">
            {{ emailError }}
          </p>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('provisioning.admin.cancelBtn')"
          severity="secondary"
          text
          @click="closeDialog"
        />
        <Button
          :label="t('provisioning.admin.saveBtn')"
          :disabled="!canSave"
          :loading="saving"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
