<script setup lang="ts">
/**
 * 寄合詳細 — 「決まったこと」セクション（F17.2 Wave1 ②寄合後半戦 §4.2.4/§4.4）。
 *
 * - 幹事＋村長/長老のみ編集可（`canEdit`。BE も PATCH で同条件をガード）
 * - CANCELLED では編集 UI を出さない（`canWrite` が false のとき編集ボタンを隠す・§4.5）
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'

const props = defineProps<{
  decisionsNote: string | null
  /** 幹事＋村長/長老か */
  canEdit: boolean
  /** 書込み可能な寄合状態か（CONFIRMED のみ・CANCELLED は編集ボタンを隠す） */
  canWrite: boolean
  saving: boolean
}>()

const emit = defineEmits<{
  save: [note: string]
}>()

const { t } = useI18n()
const editing = ref(false)
const draft = ref(props.decisionsNote ?? '')

watch(
  () => props.decisionsNote,
  (v) => {
    if (!editing.value) draft.value = v ?? ''
  },
)

function startEdit() {
  draft.value = props.decisionsNote ?? ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  draft.value = props.decisionsNote ?? ''
}

function save() {
  emit('save', draft.value)
  editing.value = false
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="flex items-center justify-between">
      <h3 class="font-semibold">
        {{ t('village.meetup.decisions.title') }}
      </h3>
      <Button
        v-if="canEdit && canWrite && !editing"
        :label="t('village.meetup.decisions.edit')"
        icon="pi pi-pencil"
        size="small"
        text
        @click="startEdit"
      />
    </div>

    <template v-if="editing">
      <Textarea
        v-model="draft"
        rows="3"
        class="w-full"
        :placeholder="t('village.meetup.decisions.placeholder')"
      />
      <div class="flex items-center gap-2 justify-end">
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          size="small"
          @click="cancelEdit"
        />
        <Button
          :label="t('village.action.save')"
          icon="pi pi-check"
          severity="primary"
          size="small"
          :loading="saving"
          @click="save"
        />
      </div>
    </template>
    <p v-else-if="decisionsNote" class="whitespace-pre-wrap text-sm">
      {{ decisionsNote }}
    </p>
    <p v-else class="text-xs text-surface-500">
      {{ t('village.meetup.decisions.empty') }}
    </p>
  </div>
</template>
