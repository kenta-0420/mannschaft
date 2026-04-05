<script setup lang="ts">
const props = defineProps<{
  teamId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  issued: [ticket: { ticketNumber: string; position: number; estimatedWaitMinutes: number }]
}>()

const queueApi = useQueueApi()
const notification = useNotification()

interface Category {
  id: number
  name: string
  prefix: string
}
interface Counter {
  id: number
  name: string
  categoryId: number
}

const categories = ref<Category[]>([])
const counters = ref<Counter[]>([])
const submitting = ref(false)
const form = ref({
  categoryId: null as number | null,
  counterId: null as number | null,
  phoneNumber: '',
})

const filteredCounters = computed(() =>
  form.value.categoryId ? counters.value.filter((c) => c.categoryId === form.value.categoryId) : [],
)

async function loadOptions() {
  const [catRes, cntRes] = await Promise.all([
    queueApi.getCategories(props.teamId),
    queueApi.getCounters(props.teamId),
  ])
  categories.value = catRes.data as Category[]
  counters.value = cntRes.data as Counter[]
}

async function submit() {
  if (!form.value.categoryId) return
  submitting.value = true
  try {
    const res = await queueApi.createCounterTicket(props.teamId, form.value.counterId!, {
      categoryId: form.value.categoryId,
      phoneNumber: form.value.phoneNumber.trim() || undefined,
    })
    const ticket = res.data as {
      ticketNumber: string
      position: number
      estimatedWaitMinutes: number
    }
    notification.success(`受付番号: ${ticket.ticketNumber}`)
    emit('issued', ticket)
    close()
  } catch {
    notification.error('受付に失敗しました')
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
  form.value = { categoryId: null, counterId: null, phoneNumber: '' }
}

watch(
  () => props.visible,
  (v) => {
    if (v) loadOptions()
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    header="受付"
    :style="{ width: '400px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >カテゴリ <span class="text-red-500">*</span></label
        >
        <Select
          v-model="form.categoryId"
          :options="categories"
          option-label="name"
          option-value="id"
          class="w-full"
          placeholder="選択"
        />
      </div>
      <div v-if="filteredCounters.length > 0">
        <label class="mb-1 block text-sm font-medium">窓口（任意）</label>
        <Select
          v-model="form.counterId"
          :options="filteredCounters"
          option-label="name"
          option-value="id"
          class="w-full"
          placeholder="自動割当"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">電話番号（非会員用）</label>
        <InputText v-model="form.phoneNumber" class="w-full" placeholder="090-xxxx-xxxx" />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        label="受付する"
        icon="pi pi-ticket"
        :loading="submitting"
        :disabled="!form.categoryId"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
