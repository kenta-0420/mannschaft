<script setup lang="ts">
import type { ScopeFolder, ScopeType } from '~/types/scopeFolder'

/**
 * F15.3 招待画面に挿入する任意選択フォルダピッカー。
 *
 * 設計書 F15.3 §7.4 に準拠。
 *
 * 役割:
 *  - 受領 scopeType に対応するフォルダ一覧を `useScopeFoldersStore` から取得
 *  - 「未選択（未分類に自動配置）」をデフォルト option として最上位に表示
 *  - 「＋ 新規フォルダ作成」インラインボタンで既存 `ScopeFolderEditDialog` を展開
 *  - 新規作成完了時はそのフォルダを自動選択
 *  - 完全任意。未選択でも参加可能（プレースホルダ "未分類に自動配置されます" を明示）
 *
 * ADHD 配慮:
 *  - 任意であることをラベルとヘルプテキストの双方で明示
 *  - 参加ボタンへフォーカスを移しても操作を阻害しない
 *
 * a11y:
 *  - `<label>` ＋ `<select>` 結合
 *  - 新規作成ボタンは `aria-label` 付与
 */

interface Props {
  scopeType: ScopeType
  /** v-model: 選択フォルダ ID。`null` は未選択（"未分類" に自動配置）。 */
  modelValue?: number | null
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: null,
})

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()
const foldersStore = useScopeFoldersStore()

const loading = ref(false)
const showCreateDialog = ref(false)

/** 「未分類」を除外したユーザー作成フォルダのみを選択肢にする。 */
const folders = computed<ScopeFolder[]>(() =>
  foldersStore.customFoldersFor(props.scopeType),
)

/** select 要素の v-model 用。number か空文字（未選択）で扱う。 */
const selectedValue = computed<string>({
  get: () => (props.modelValue == null ? '' : String(props.modelValue)),
  set: (val: string) => {
    if (val === '') {
      emit('update:modelValue', null)
    }
    else {
      emit('update:modelValue', Number(val))
    }
  },
})

/**
 * フォルダ一覧をロード。store がまだ取得していない場合のみフェッチする。
 * 既に取得済みなら state 再利用（無駄な API 呼び出し回避）。
 */
async function ensureFoldersLoaded() {
  const list = foldersStore.foldersFor(props.scopeType)
  if (list.length > 0) return
  loading.value = true
  try {
    await foldersStore.fetchAll(props.scopeType)
  }
  finally {
    loading.value = false
  }
}

/** 新規作成ダイアログを開く。 */
function openCreateDialog() {
  showCreateDialog.value = true
}

/**
 * 新規作成完了時、そのフォルダを自動選択する。
 *
 * `ScopeFolderEditDialog` は `useScopeFolderApi` を直接呼び出すため、
 * `useScopeFoldersStore` の state には反映されない。整合を取るため、
 * Pinia ストアに同じフォルダを upsert してから選択状態を反映する。
 */
function onFolderSaved(folder: ScopeFolder) {
  foldersStore.upsertFolder(props.scopeType, folder)
  emit('update:modelValue', folder.id)
}

onMounted(() => {
  ensureFoldersLoaded()
})

/** scopeType が後から変わった場合に追従。 */
watch(
  () => props.scopeType,
  () => {
    ensureFoldersLoaded()
  },
)
</script>

<template>
  <div class="invite-folder-picker flex flex-col gap-1" data-testid="invite-folder-picker">
    <label
      :for="`invite-folder-picker-${scopeType}`"
      class="text-sm font-medium text-left"
    >
      {{ t('scopeFolder.assignOnJoin.label') }}
    </label>

    <div class="flex items-center gap-2">
      <select
        :id="`invite-folder-picker-${scopeType}`"
        v-model="selectedValue"
        :disabled="loading"
        :aria-describedby="`invite-folder-picker-hint-${scopeType}`"
        class="flex-1 rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary disabled:cursor-not-allowed disabled:opacity-60"
      >
        <option value="">
          {{ t('scopeFolder.assignOnJoin.unselected') }}
        </option>
        <option
          v-for="folder in folders"
          :key="folder.id"
          :value="String(folder.id)"
        >
          {{ folder.name }}
        </option>
      </select>

      <Button
        type="button"
        icon="pi pi-plus"
        severity="secondary"
        outlined
        size="small"
        :aria-label="t('scopeFolder.nav.createNew')"
        :title="t('scopeFolder.nav.createNew')"
        @click="openCreateDialog"
      />
    </div>

    <p
      :id="`invite-folder-picker-hint-${scopeType}`"
      class="text-xs text-surface-500 text-left"
    >
      {{ t('scopeFolder.assignOnJoin.defaultHint') }}
    </p>

    <ScopeFolderEditDialog
      v-model:visible="showCreateDialog"
      :scope-type="scopeType"
      @saved="onFolderSaved"
    />
  </div>
</template>
