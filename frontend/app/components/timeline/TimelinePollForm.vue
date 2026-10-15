<script setup lang="ts">
const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  created: [poll: { question: string; options: string[]; expiresAt?: string }]
}>()

const question = ref('')
const options = ref(['', ''])
const expiresAt = ref<Date | null>(null)

function addOption() {
  if (options.value.length < 4) {
    options.value.push('')
  }
}

function removeOption(index: number) {
  if (options.value.length > 2) {
    options.value.splice(index, 1)
  }
}

const isValid = computed(() => {
  return question.value.trim().length > 0
    && options.value.filter(o => o.trim().length > 0).length >= 2
})

function onSubmit() {
  if (!isValid.value) return
  const poll: { question: string; options: string[]; expiresAt?: string } = {
    question: question.value.trim(),
    options: options.value.filter(o => o.trim()).map(o => o.trim()),
  }
  if (expiresAt.value) {
    poll.expiresAt = expiresAt.value.toISOString()
  }
  emit('created', poll)
  resetForm()
  visible.value = false
}

function resetForm() {
  question.value = ''
  options.value = ['', '']
  expiresAt.value = null
}
</script>

<template>
  <Dialog v-model:visible="visible" header="投票を作成" modal class="w-full max-w-lg">
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">質問</label>
        <InputText v-model="question" class="w-full" placeholder="投票の質問を入力" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">選択肢（2〜4つ）</label>
        <div class="flex flex-col gap-2">
          <div v-for="(_, index) in options" :key="index" class="flex items-center gap-2">
            <InputText
              v-model="options[index]"
              class="flex-1"
              :placeholder="`選択肢 ${index + 1}`"
            />
            <Button
              v-if="options.length > 2"
              icon="pi pi-times"
              text
              rounded
              severity="danger"
              size="small"
              @click="removeOption(index)"
            />
          </div>
          <Button
            v-if="options.length < 4"
            label="選択肢を追加"
            icon="pi pi-plus"
            text
            size="small"
            @click="addOption"
          />
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">締切日時（任意）</label>
        <DatePicker v-model="expiresAt" show-time class="w-full" placeholder="締切なし" />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button label="作成" :disabled="!isValid" @click="onSubmit" />
    </template>
  </Dialog>
</template>
