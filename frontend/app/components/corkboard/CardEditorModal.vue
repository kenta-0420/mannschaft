<script setup lang="ts">
/**
 * F09.8 コルクボードカード作成・編集モーダル（薄いシェル）。
 *
 * 責務:
 *  - Dialog 殻 + フッターアクション
 *  - `useCardEditorForm()` の唯一の呼出点 → provide で子コンポーネントに共有
 *  - 保存ハンドラ（createCard / updateCard）+ トースト + エラーレポート
 *  - `<component :is="typeFieldsComponent" />` で型別子を動的切替
 *
 * 親子関係:
 *  - 親（呼出元）: `pages/corkboard/[id].vue`
 *  - 子（フィールド分割）: `card-editor/CardEditor{Common,Memo,Url,Reference,SectionHeader}Fields.vue`
 *
 * 外向き API（呼出元との互換）: 設計書 §4 (POST/PUT /api/v1/corkboards/{id}/cards)
 *   v-model:visible / mode / board-id / card / default-position
 *   @save / @cancel
 */
import { computed, provide, toRef, watch } from 'vue'
import { useToast } from 'primevue/usetoast'
import type { CorkboardCardDetail } from '~/types/corkboard'
import CardEditorCommonFields from './card-editor/CardEditorCommonFields.vue'
import CardEditorMemoFields from './card-editor/CardEditorMemoFields.vue'
import CardEditorUrlFields from './card-editor/CardEditorUrlFields.vue'
import CardEditorReferenceFields from './card-editor/CardEditorReferenceFields.vue'
import CardEditorSectionHeaderFields from './card-editor/CardEditorSectionHeaderFields.vue'
import {
  CardEditorContextKey,
  CardEditorModeKey,
} from './card-editor/useCardEditorContext'

interface Props {
  /** モーダル開閉状態 (v-model:visible) */
  visible: boolean
  /** create: 新規作成 / edit: 既存カード編集 */
  mode: 'create' | 'edit'
  /** 対象ボード ID（作成・更新の URL パスに使う） */
  boardId: number
  /** edit モード時の対象カード */
  card?: CorkboardCardDetail | null
  /** create モード時の初期座標（省略時 0,0） */
  defaultPosition?: { x: number; y: number }
}

const props = withDefaults(defineProps<Props>(), {
  card: null,
  defaultPosition: () => ({ x: 0, y: 0 }),
})

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save', card: CorkboardCardDetail): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const toast = useToast()
const { captureQuiet } = useErrorReport()
const { createCard, updateCard } = useCorkboardApi()

// ----- フォームロジック（唯一の呼出点） -----

const modeRef = toRef(props, 'mode')
const cardRef = toRef(props, 'card')
const boardIdRef = toRef(props, 'boardId')
const defaultPositionRef = toRef(props, 'defaultPosition')

const form = useCardEditorForm(modeRef, cardRef, boardIdRef, defaultPositionRef)
const { cardType, saving, resetForm, validate, buildCreatePayload, buildUpdatePayload } = form

// 子コンポーネントへ provide
provide(CardEditorContextKey, form)
provide(CardEditorModeKey, modeRef)

// ----- visible (v-model 連携) -----

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

// モーダルが開いた瞬間にフォームを初期化する
watch(
  () => props.visible,
  (v) => {
    if (v) resetForm()
  },
)

// ----- 型別フィールドの動的切替 -----

const typeFieldsComponent = computed(() => {
  switch (cardType.value) {
    case 'REFERENCE':
      return CardEditorReferenceFields
    case 'MEMO':
      return CardEditorMemoFields
    case 'URL':
      return CardEditorUrlFields
    case 'SECTION_HEADER':
      return CardEditorSectionHeaderFields
    default:
      return CardEditorMemoFields
  }
})

// ----- 保存・キャンセル -----

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    if (props.mode === 'create') {
      const res = await createCard(props.boardId, buildCreatePayload())
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.createSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    } else if (props.mode === 'edit' && props.card) {
      const res = await updateCard(props.boardId, props.card.id, buildUpdatePayload())
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.editSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    }
    dialogVisible.value = false
  } catch (e) {
    captureQuiet(e, {
      context: `CardEditorModal: ${props.mode} 保存失敗`,
    })
    toast.add({
      severity: 'error',
      summary:
        props.mode === 'create'
          ? t('corkboard.toast.createError')
          : t('corkboard.toast.editError'),
      life: 3500,
    })
  } finally {
    saving.value = false
  }
}

function cancel() {
  emit('cancel')
  dialogVisible.value = false
}

const dialogHeader = computed(() =>
  props.mode === 'create'
    ? t('corkboard.modal.createTitle')
    : t('corkboard.modal.editTitle'),
)
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    modal
    :header="dialogHeader"
    :closable="!saving"
    :close-on-escape="!saving"
    :style="{ width: '560px', maxWidth: '95vw' }"
    :pt="{
      root: { 'aria-modal': 'true', 'data-testid': 'card-editor-modal' },
    }"
  >
    <form class="flex flex-col gap-4" data-testid="card-editor-form" @submit.prevent="save">
      <CardEditorCommonFields>
        <component :is="typeFieldsComponent" />
      </CardEditorCommonFields>
    </form>

    <template #footer>
      <Button
        :label="t('corkboard.modal.cancel')"
        severity="secondary"
        text
        :disabled="saving"
        data-testid="card-editor-cancel-button"
        @click="cancel"
      />
      <Button
        :label="t('corkboard.modal.save')"
        icon="pi pi-check"
        :loading="saving"
        data-testid="card-editor-save-button"
        @click="save"
      />
    </template>
  </Dialog>
</template>
