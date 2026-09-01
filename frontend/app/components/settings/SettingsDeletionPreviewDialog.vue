<script setup lang="ts">
import type { DeletionPreviewResponse, LastAdminScope } from '~/composables/useGdprApi'

const props = defineProps<{
  visible: boolean
  hasPassword?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirmed: [currentPassword: string | null]
}>()

const { getDeletionPreview } = useGdprApi()
const notification = useNotification()
const { t } = useI18n()

const preview = ref<DeletionPreviewResponse | null>(null)
const loadingPreview = ref(false)
const currentPassword = ref('')

interface SummaryRow {
  category: string
  count: number
}

interface AnonymizedRow {
  entity: string
  field: string
}

const deletedRows = computed<SummaryRow[]>(() => {
  if (!preview.value?.dataSummary) return []
  return Object.entries(preview.value.dataSummary).map(([category, count]) => ({
    category,
    count,
  }))
})

const anonymizedRows = computed<AnonymizedRow[]>(() => {
  return preview.value?.anonymized ?? []
})

/**
 * 柱①「ADMINゼロ根治」AC1 — 他メンバーが残る唯一ADMINスコープ一覧。
 * 1件でもあれば退会（削除）ボタンを無効化する（BE GDPR_011 と同じ判定条件を FE 側でも表現）。
 */
const lastAdminScopes = computed<LastAdminScope[]>(() => {
  return preview.value?.lastAdminScopes ?? []
})

const isBlockedByLastAdmin = computed(() => lastAdminScopes.value.length > 0)

async function loadPreview() {
  if (loadingPreview.value) return
  loadingPreview.value = true
  try {
    const res = await getDeletionPreview()
    preview.value = res?.data ?? null
  } catch {
    notification.error(t('deletion_preview.fetch_error'))
  } finally {
    loadingPreview.value = false
  }
}

/** 親（account.vue）が GDPR_011 で退会に失敗した際に、最新の判定を取り直すために呼ぶ。 */
async function reloadPreview() {
  preview.value = null
  await loadPreview()
}

defineExpose({ reloadPreview })

watch(
  () => props.visible,
  (val) => {
    if (val && !preview.value) {
      loadPreview()
    }
  },
)

/** スコープ種別ごとの管理導線（一覧ページ）へ遷移する。ダイアログは閉じる。 */
function goToScopeManagement(scope: LastAdminScope) {
  emit('update:visible', false)
  navigateTo(scope.scopeType === 'ORGANIZATION' ? '/organizations' : '/teams')
}

function cancel() {
  currentPassword.value = ''
  emit('update:visible', false)
}

function confirm() {
  if (isBlockedByLastAdmin.value) return
  emit('confirmed', props.hasPassword ? currentPassword.value : null)
  currentPassword.value = ''
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('deletion_preview.dialog_title')"
    :modal="true"
    class="w-full max-w-2xl"
    data-testid="settings-deletion-preview-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-5">
      <p class="font-medium text-red-600">
        {{ $t('deletion_preview.confirm_message') }}
      </p>

      <div v-if="loadingPreview" class="flex items-center gap-2 text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
        <span>{{ $t('button.loading') }}</span>
      </div>

      <template v-else-if="preview">
        <!-- 柱①「ADMINゼロ根治」AC1: 唯一ADMINスコープが残っている間は退会不可 -->
        <div
          v-if="lastAdminScopes.length"
          data-testid="settings-deletion-preview-last-admin-block"
          class="rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-800 dark:bg-red-900/20"
        >
          <p class="mb-1 text-sm font-semibold text-red-700 dark:text-red-400">
            {{ $t('deletion_preview.last_admin_title') }}
          </p>
          <p class="mb-3 text-sm text-red-700 dark:text-red-400">
            {{ $t('deletion_preview.last_admin_description') }}
          </p>
          <ul class="space-y-2">
            <li
              v-for="scope in lastAdminScopes"
              :key="`${scope.scopeType}:${scope.scopeId}`"
              class="flex flex-wrap items-center justify-between gap-2 rounded-md bg-white/60 p-2 text-sm dark:bg-surface-900/40"
            >
              <span class="font-medium">
                {{
                  scope.scopeType === 'ORGANIZATION'
                    ? $t('deletion_preview.scope_type_organization')
                    : $t('deletion_preview.scope_type_team')
                }}
                「{{ scope.scopeName }}」
                <template v-if="scope.otherMembersCount > 0">
                  （{{ $t('deletion_preview.other_members_count', { count: scope.otherMembersCount }) }}）
                </template>
              </span>
              <span class="flex gap-2">
                <Button
                  translate="no"
                  size="small"
                  severity="secondary"
                  :label="$t('deletion_preview.transfer_ownership_button')"
                  :data-testid="`settings-deletion-preview-transfer-${scope.scopeType}-${scope.scopeId}`"
                  @click="goToScopeManagement(scope)"
                />
                <Button
                  translate="no"
                  size="small"
                  severity="secondary"
                  outlined
                  :label="$t('deletion_preview.archive_button')"
                  :data-testid="`settings-deletion-preview-archive-${scope.scopeType}-${scope.scopeId}`"
                  @click="goToScopeManagement(scope)"
                />
              </span>
            </li>
          </ul>
        </div>

        <div v-if="preview.warnings?.length" class="rounded-lg border border-yellow-200 bg-yellow-50 p-3 dark:border-yellow-800 dark:bg-yellow-900/20">
          <p class="mb-1 text-sm font-semibold text-yellow-700 dark:text-yellow-400">{{ $t('deletion_preview.warning_title') }}</p>
          <ul class="list-inside list-disc space-y-1">
            <li
              v-for="(w, i) in preview.warnings"
              :key="i"
              class="text-sm text-yellow-700 dark:text-yellow-400"
            >
              {{ w }}
            </li>
          </ul>
        </div>

        <div>
          <h3 class="mb-2 text-sm font-semibold">{{ $t('deletion_preview.deleted_data_title') }}</h3>
          <DataTable
            :value="deletedRows"
            size="small"
            class="text-sm"
            :row-hover="true"
          >
            <Column field="category" :header="$t('deletion_preview.table_category')" />
            <Column field="count" :header="$t('deletion_preview.table_count')">
              <template #body="{ data }">{{ $t('deletion_preview.table_count_unit', { count: data.count }) }}</template>
            </Column>
          </DataTable>
        </div>

        <div v-if="anonymizedRows.length">
          <h3 class="mb-2 text-sm font-semibold">{{ $t('deletion_preview.anonymized_data_title') }}</h3>
          <DataTable
            :value="anonymizedRows"
            size="small"
            class="text-sm"
            :row-hover="true"
          >
            <Column field="entity" :header="$t('deletion_preview.table_entity')" />
            <Column field="field" :header="$t('deletion_preview.table_field')" />
          </DataTable>
        </div>

        <p v-if="preview.retentionDays" class="text-xs text-surface-500">
          {{ $t('settings.delete_account.retention_note', { days: preview.retentionDays }) }}
        </p>
      </template>

      <div v-if="hasPassword" class="flex flex-col gap-2">
        <label for="deletePassword" class="text-sm font-semibold">
          {{ $t('settings.delete_account.password_confirm_label') }}
        </label>
        <Password
          v-model="currentPassword"
          input-id="deletePassword"
          :feedback="false"
          toggle-mask
          fluid
          :placeholder="$t('settings.delete_account.password_placeholder')"
        />
      </div>
    </div>

    <template #footer>
      <div class="flex w-full flex-col items-end gap-2">
        <p
          v-if="isBlockedByLastAdmin"
          data-testid="settings-deletion-preview-blocked-notice"
          class="text-xs text-red-600"
        >
          {{ $t('deletion_preview.blocked_notice') }}
        </p>
        <div class="flex justify-end gap-2">
          <Button
            translate="no"
            :label="$t('deletion_preview.cancel_button')"
            severity="secondary"
            @click="cancel"
          />
          <Button
            translate="no"
            :label="$t('deletion_preview.delete_button')"
            severity="danger"
            icon="pi pi-trash"
            data-testid="settings-deletion-preview-delete-button"
            :disabled="loadingPreview || isBlockedByLastAdmin || (hasPassword && !currentPassword)"
            @click="confirm"
          />
        </div>
      </div>
    </template>
  </Dialog>
</template>
