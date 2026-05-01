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
    notification.error('削除プレビューの取得に失敗しました')
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
    header="アカウント削除の確認"
    :modal="true"
    class="w-full max-w-2xl"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-5">
      <p class="font-medium text-red-600">
        この操作は取り消せません。本当にアカウントを削除しますか？
      </p>

      <div v-if="loadingPreview" class="flex items-center gap-2 text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
        <span>削除プレビューを読み込み中...</span>
      </div>

      <template v-else-if="preview">
        <div v-if="preview.warnings?.length" class="rounded-lg border border-yellow-200 bg-yellow-50 p-3 dark:border-yellow-800 dark:bg-yellow-900/20">
          <p class="mb-1 text-sm font-semibold text-yellow-700 dark:text-yellow-400">注意事項</p>
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
          <h3 class="mb-2 text-sm font-semibold">削除されるデータ</h3>
          <DataTable
            :value="deletedRows"
            size="small"
            class="text-sm"
            :row-hover="true"
          >
            <Column field="category" header="カテゴリ" />
            <Column field="count" header="件数">
              <template #body="{ data }">{{ data.count }}件</template>
            </Column>
          </DataTable>
        </div>

        <div v-if="anonymizedRows.length">
          <h3 class="mb-2 text-sm font-semibold">匿名化されるデータ</h3>
          <DataTable
            :value="anonymizedRows"
            size="small"
            class="text-sm"
            :row-hover="true"
          >
            <Column field="entity" header="対象" />
            <Column field="field" header="フィールド" />
          </DataTable>
        </div>

        <p v-if="preview.retentionDays" class="text-xs text-surface-500">
          ※ 一部のデータは法的要件により {{ preview.retentionDays }} 日間保持された後に削除されます。
        </p>
      </template>

      <div v-if="hasPassword" class="flex flex-col gap-2">
        <label for="deletePassword" class="text-sm font-semibold">
          確認のため現在のパスワードを入力してください
        </label>
        <Password
          input-id="deletePassword"
          v-model="currentPassword"
          :feedback="false"
          toggle-mask
          fluid
          placeholder="現在のパスワード"
        />
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          label="キャンセル"
          severity="secondary"
          @click="cancel"
        />
        <Button
          label="削除する"
          severity="danger"
          icon="pi pi-trash"
          :disabled="loadingPreview || (hasPassword && !currentPassword)"
          @click="confirm"
        />
      </div>
    </template>
  </Dialog>
</template>
