<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('shift.visualReview.title')"
    modal
    :style="{ width: '520px' }"
    :draggable="false"
  >
    <div class="space-y-4">
      <!-- チェックリスト -->
      <div class="space-y-3">
        <div v-for="(item, idx) in checkItems" :key="idx" class="flex items-start gap-3">
          <Checkbox
            v-model="checkedItems"
            :value="idx"
            :input-id="`review-check-${idx}`"
          />
          <label :for="`review-check-${idx}`" class="text-sm text-surface-700 cursor-pointer leading-relaxed">
            {{ item }}
          </label>
        </div>
      </div>

      <!-- メモ入力 -->
      <div class="mt-4">
        <label class="block text-sm font-medium text-surface-700 mb-1">
          {{ $t('shift.visualReview.note') }}
        </label>
        <Textarea v-model="note" rows="3" class="w-full" />
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        text
        @click="visible = false"
      />
      <Button
        :label="$t('shift.visualReview.submit')"
        :disabled="!allChecked"
        icon="pi pi-check"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>

<script setup lang="ts">
const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  submit: [note: string | undefined]
}>()

const { t } = useI18n()

const note = ref('')
const checkedItems = ref<number[]>([])

const checkItems = computed(() => [
  t('shift.visualReview.check1'),
  t('shift.visualReview.check2'),
  t('shift.visualReview.check3'),
  t('shift.visualReview.check4'),
  t('shift.visualReview.check5'),
])

const allChecked = computed(() => checkedItems.value.length === checkItems.value.length)

function onSubmit(): void {
  if (!allChecked.value) return
  emit('submit', note.value.trim() || undefined)
  visible.value = false
  checkedItems.value = []
  note.value = ''
}
</script>
