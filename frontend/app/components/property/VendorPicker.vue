<script setup lang="ts">
/**
 * 業者選択（オートコンプリート）（F09.13 Phase 1-ε）。
 *
 * - PrimeVue AutoComplete でオートコンプリート（useVendorApi.suggest 経由・最大10件）。
 * - v-model:modelValue で vendorId (number | null) を双方向束縛する。
 * - 親が scope/scopeId を提供。サジェストは scope 限定で取得。
 *
 * 「新規業者を作成」リンクは Phase 1 では業者マスタ管理ページへの遷移リンクのみ提供する
 * （モーダル内モーダルの UX 衝突を避け、Phase 2 で in-place 作成を別軍議化）。
 */
import type { ScopeName } from '~/types/property'
import type { VendorSuggestionResponse } from '~/types/vendor'

const props = defineProps<{
  modelValue: number | null | undefined
  scope: ScopeName
  scopeId: string
  /** 初期表示用の業者名（編集時にあらかじめ入力欄を埋めたい場合に渡す） */
  initialName?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()
const vendorApi = useVendorApi(props.scope, props.scopeId)

interface PickerItem {
  id: number
  name: string
  nameKana: string | null
  display: string
}

const selected = ref<PickerItem | null>(null)
const suggestions = ref<PickerItem[]>([])

function toItem(v: VendorSuggestionResponse): PickerItem {
  return {
    id: v.id,
    name: v.name,
    nameKana: v.nameKana,
    display: v.nameKana ? `${v.name} (${v.nameKana})` : v.name,
  }
}

async function onSearch(event: { query: string }) {
  const q = event.query?.trim() ?? ''
  if (q.length === 0) {
    suggestions.value = []
    return
  }
  try {
    const list = await vendorApi.suggest(q)
    suggestions.value = list.map(toItem)
  } catch {
    suggestions.value = []
  }
}

function onSelect(event: { value: PickerItem }) {
  selected.value = event.value
  emit('update:modelValue', event.value?.id ?? null)
}

function onClear() {
  selected.value = null
  emit('update:modelValue', null)
}

// 親が modelValue を変更した場合の同期（編集ロード時など）
watch(
  () => props.modelValue,
  async (newId) => {
    if (newId === null || newId === undefined) {
      selected.value = null
      return
    }
    if (selected.value && selected.value.id === newId) return
    // 既に initialName が渡っていればそれで埋める（API call を抑制）
    if (props.initialName) {
      selected.value = {
        id: newId,
        name: props.initialName,
        nameKana: null,
        display: props.initialName,
      }
      return
    }
    try {
      const vendor = await vendorApi.get(newId)
      selected.value = {
        id: vendor.id,
        name: vendor.name,
        nameKana: vendor.nameKana,
        display: vendor.nameKana ? `${vendor.name} (${vendor.nameKana})` : vendor.name,
      }
    } catch {
      // 業者が削除済みなどで取得失敗した場合は表示クリア
      selected.value = null
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex items-center gap-2">
    <AutoComplete
      v-model="selected"
      :suggestions="suggestions"
      option-label="display"
      :placeholder="t('property.vendor.search')"
      :disabled="disabled"
      :complete-on-focus="true"
      class="flex-1"
      data-testid="property-vendor-picker"
      @complete="onSearch"
      @item-select="onSelect"
      @clear="onClear"
    />
    <Button
      v-if="selected && !disabled"
      icon="pi pi-times"
      severity="secondary"
      text
      rounded
      :aria-label="t('property.actions.cancel')"
      @click="onClear"
    />
  </div>
</template>
