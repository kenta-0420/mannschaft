<script setup lang="ts">
import type { DeletionPreviewResponse } from '~/composables/useGdprApi'

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

watch(
  () => props.visible,
  (val) => {
    if (val && !preview.value) {
      loadPreview()
    }
  },
)

function cancel() {
  currentPassword.value = ''
  emit('update:visible', false)
}

function confirm() {
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
          :disabled="loadingPreview || (hasPassword && !currentPassword)"
          @click="confirm"
        />
      </div>
    </template>
  </Dialog>
</template>
