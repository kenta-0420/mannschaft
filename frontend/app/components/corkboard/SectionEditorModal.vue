<script setup lang="ts">
/**
 * F09.8 Phase E: コルクボードセクション (group) 作成・編集モーダル。
 *
 * セクション = ボード上にゆるく区画を切るための領域。カード自体は absolute 配置のため
 * セクションは見出し + 折りたたみ可能領域として機能する。所属関係は
 * `corkboard_card_groups` 中間テーブルで管理。
 *
 * 設計書 §4 `POST /api/v1/corkboards/{id}/groups` / `PUT .../groups/{groupId}` 準拠。
 *
 * バックエンド DTO（{@link com.mannschaft.app.corkboard.dto.CreateGroupRequest} /
 * {@link UpdateGroupRequest}）に厳格整合。
 *
 * 注: バックエンド DTO に `description` フィールドは無いため Phase E では扱わない。
 * 設計書文中で言及される「概要文」UI は、DDL 追加と DTO 拡張を別 Phase で行ってから載せる。
 *
 * 親 (`pages/corkboard/[id].vue`) からは:
 *   <SectionEditorModal
 *     v-model:visible="isOpen"
 *     mode="create"
 *     :board-id="board.id"
 *     @save="onSectionSaved"
 *   />
 * のように使う。`mode='edit'` の場合は `section` を渡す。
 */
import { useToast } from 'primevue/usetoast'
import type {
  CorkboardGroupDetail,
  CreateGroupRequest,
  UpdateGroupRequest,
} from '~/types/corkboard'

interface Props {
  /** モーダル開閉状態 (v-model:visible) */
  visible: boolean
  /** create: 新規作成 / edit: 既存セクション編集 */
  mode: 'create' | 'edit'
  /** 対象ボード ID */
  boardId: number
  /** edit モード時の対象セクション */
  section?: CorkboardGroupDetail | null
}

const props = withDefaults(defineProps<Props>(), {
  section: null,
})

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save', section: CorkboardGroupDetail): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const toast = useToast()
const { captureQuiet } = useErrorReport()
const { createGroup, updateGroup } = useCorkboardApi()

// ----- フォーム状態 -----

const NAME_MAX_LENGTH = 100

const sectionName = ref<string>('')
const isCollapsed = ref<boolean>(false)

const saving = ref(false)
const errors = ref<Record<string, string>>({})

// ----- visible (v-model 連携) -----

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

// モーダルが開いた瞬間にフォームを初期化
watch(
  () => props.visible,
  (v) => {
    if (v) {
      resetForm()
      // 初期フォーカス（A11y: モーダルオープン時に名前入力欄へフォーカス）
      nextTick(() => {
        const el = document.getElementById('sectionEditorName') as HTMLInputElement | null
        el?.focus()
      })
    }
  },
)

function resetForm() {
  errors.value = {}
  if (props.mode === 'edit' && props.section) {
    sectionName.value = props.section.name ?? ''
    isCollapsed.value = props.section.isCollapsed ?? false
  } else {
    sectionName.value = ''
    isCollapsed.value = false
  }
}

// ----- バリデーション -----

function validate(): boolean {
  const e: Record<string, string> = {}
  const trimmed = sectionName.value.trim()
  if (!trimmed) {
    e.name = t('corkboard.validation.sectionTitleRequired')
  } else if (trimmed.length > NAME_MAX_LENGTH) {
    e.name = t('corkboard.validation.sectionTitleTooLong', { max: NAME_MAX_LENGTH })
  }
  errors.value = e
  return Object.keys(e).length === 0
}

// ----- 保存 -----

function buildCreatePayload(): CreateGroupRequest {
  return {
    name: sectionName.value.trim(),
    isCollapsed: isCollapsed.value,
  }
}

function buildUpdatePayload(): UpdateGroupRequest {
  return {
    name: sectionName.value.trim(),
    isCollapsed: isCollapsed.value,
  }
}

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    if (props.mode === 'create') {
      const res = await createGroup(props.boardId, buildCreatePayload())
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.sectionCreateSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    } else if (props.mode === 'edit' && props.section) {
      const res = await updateGroup(
        props.boardId,
        props.section.id,
        buildUpdatePayload(),
      )
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.sectionEditSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    }
    dialogVisible.value = false
  } catch (e) {
    captureQuiet(e, {
      context: `SectionEditorModal: ${props.mode} 保存失敗`,
    })
    toast.add({
      severity: 'error',
      summary:
        props.mode === 'create'
          ? t('corkboard.toast.sectionCreateError')
          : t('corkboard.toast.sectionEditError'),
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

// ----- アクセシビリティ -----

const dialogHeader = computed(() =>
  props.mode === 'create'
    ? t('corkboard.modal.sectionCreateTitle')
    : t('corkboard.modal.sectionEditTitle'),
)
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    modal
    :header="dialogHeader"
    :closable="!saving"
    :close-on-escape="!saving"
    :style="{ width: '480px', maxWidth: '95vw' }"
    :pt="{
      root: { 'aria-modal': 'true', 'data-testid': 'section-editor-modal' },
    }"
  >
    <form
      class="flex flex-col gap-4"
      data-testid="section-editor-form"
      @submit.prevent="save"
    >
      <!-- セクション名（必須） -->
      <div class="flex flex-col gap-1">
        <label for="sectionEditorName" class="text-sm font-medium">
          {{ t('corkboard.modal.sectionTitle') }}
          <span class="text-red-500" aria-hidden="true">*</span>
        </label>
        <InputText
          id="sectionEditorName"
          v-model="sectionName"
          :maxlength="NAME_MAX_LENGTH"
          class="w-full"
          :aria-invalid="!!errors.name"
          :aria-describedby="errors.name ? 'sectionEditorNameError' : undefined"
          data-testid="section-editor-name-input"
          required
        />
        <small
          v-if="errors.name"
          id="sectionEditorNameError"
          class="text-red-500"
          data-testid="section-editor-name-error"
        >
          {{ errors.name }}
        </small>
      </div>

      <!-- 折りたたみ初期状態 -->
      <div class="flex items-center gap-2">
        <ToggleSwitch
          v-model="isCollapsed"
          input-id="sectionEditorIsCollapsed"
          data-testid="section-editor-is-collapsed-toggle"
        />
        <label for="sectionEditorIsCollapsed" class="text-sm">
          {{ t('corkboard.modal.sectionIsCollapsed') }}
        </label>
      </div>
      <small class="text-xs text-surface-500">
        {{ t('corkboard.modal.sectionIsCollapsedHint') }}
      </small>
    </form>

    <template #footer>
      <Button
        :label="t('corkboard.modal.cancel')"
        severity="secondary"
        text
        :disabled="saving"
        data-testid="section-editor-cancel-button"
        @click="cancel"
      />
      <Button
        :label="t('corkboard.modal.save')"
        icon="pi pi-check"
        :loading="saving"
        data-testid="section-editor-save-button"
        @click="save"
      />
    </template>
  </Dialog>
</template>
