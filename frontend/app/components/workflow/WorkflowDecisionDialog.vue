<script setup lang="ts">
import type { ElectronicSeal } from '~/types/seal'

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  decisionType: 'APPROVED' | 'REJECTED'
  submitting: boolean
  isSealRequired?: boolean
  userId?: number
}>()

const emit = defineEmits<{
  submit: [comment: string, sealId: number | undefined]
}>()

const decisionComment = ref('')
const selectedSealId = ref<number | undefined>(undefined)
const seals = ref<ElectronicSeal[]>([])
const loadingSeals = ref(false)

const showSealSelector = computed(
  () => props.isSealRequired && props.decisionType === 'APPROVED',
)

async function loadSeals() {
  if (!showSealSelector.value || !props.userId) return
  loadingSeals.value = true
  try {
    const { getSeals } = useSealApi()
    seals.value = await getSeals(props.userId)
    if (seals.value.length > 0 && !selectedSealId.value) {
      selectedSealId.value = seals.value[0].sealId
    }
  } catch {
    /* silent */
  } finally {
    loadingSeals.value = false
  }
}

watch(visible, (v) => {
  if (v) {
    decisionComment.value = ''
    selectedSealId.value = undefined
    loadSeals()
  }
})

function onSubmit() {
  emit('submit', decisionComment.value.trim(), selectedSealId.value)
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="props.decisionType === 'APPROVED' ? '承認' : '却下'"
    :style="{ width: '440px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <p>
        {{ props.decisionType === 'APPROVED' ? 'この申請を承認しますか？' : 'この申請を却下しますか？' }}
      </p>

      <!-- 電子印鑑選択（承認 + isSealRequired の場合のみ表示） -->
      <div v-if="showSealSelector">
        <label class="mb-1 block text-sm font-medium">
          電子印鑑 <span class="text-red-500">*</span>
        </label>
        <div v-if="loadingSeals" class="flex items-center gap-2 text-sm text-surface-500">
          <i class="pi pi-spin pi-spinner" /> 印鑑を読み込み中...
        </div>
        <Select
          v-else
          v-model="selectedSealId"
          :options="seals"
          option-label="displayText"
          option-value="sealId"
          placeholder="印鑑を選択"
          class="w-full"
        >
          <template #option="{ option }">
            <div class="flex items-center gap-3">
              <div class="size-8 shrink-0" v-html="option.svgData" />
              <span>{{ option.displayText }}</span>
            </div>
          </template>
        </Select>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">コメント</label>
        <Textarea
          v-model="decisionComment"
          rows="3"
          class="w-full"
          placeholder="コメント（任意）"
        />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        :label="props.decisionType === 'APPROVED' ? '承認' : '却下'"
        :severity="props.decisionType === 'APPROVED' ? 'success' : 'danger'"
        :loading="submitting"
        :disabled="showSealSelector && !selectedSealId"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
