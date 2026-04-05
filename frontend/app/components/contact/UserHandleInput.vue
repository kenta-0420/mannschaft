<script setup lang="ts">
defineProps<{
  modelValue: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'availability-change': [available: boolean | null]
}>()

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()

const RESERVED = new Set([
  'admin',
  'system',
  'support',
  'mannschaft',
  'api',
  'help',
  'info',
  'null',
  'undefined',
  'me',
  'anonymous',
  'moderator',
  'bot',
  'official',
])
const HANDLE_RE = /^[a-z0-9_-]{3,30}$/

const checking = ref(false)
const available = ref<boolean | null>(null)
const validationError = ref<string | null>(null)

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function validate(val: string): string | null {
  if (!val) return null
  if (!HANDLE_RE.test(val)) return '3〜30文字の英小文字・数字・_・- のみ使用できます'
  if (RESERVED.has(val)) return 'この名前は使用できません'
  return null
}

async function checkAvailability(handle: string) {
  validationError.value = validate(handle)
  if (validationError.value || !handle) {
    available.value = null
    emit('availability-change', null)
    return
  }
  checking.value = true
  try {
    const result = await contactApi.checkHandleAvailability(handle)
    available.value = result.available
    emit('availability-change', result.available)
  } catch (e) {
    captureQuiet(e, { context: 'UserHandleInput: 重複チェック' })
    available.value = null
    emit('availability-change', null)
  } finally {
    checking.value = false
  }
}

function onInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value.toLowerCase()
  emit('update:modelValue', raw)
  available.value = null
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => checkAvailability(raw), 500)
}

onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<template>
  <div class="flex flex-col gap-1">
    <div class="relative flex items-center">
      <span class="pointer-events-none absolute left-3 select-none text-gray-400">@</span>
      <InputText
        :value="modelValue"
        :disabled="disabled"
        class="w-full pl-7"
        placeholder="handle_name"
        maxlength="30"
        @input="onInput"
      />
      <span class="absolute right-3">
        <i v-if="checking" class="pi pi-spin pi-spinner text-gray-400" />
        <i v-else-if="available === true" class="pi pi-check-circle text-green-500" />
        <i v-else-if="available === false" class="pi pi-times-circle text-red-500" />
      </span>
    </div>
    <small v-if="validationError" class="text-red-500">{{ validationError }}</small>
    <small v-else-if="available === true" class="text-green-600">使用できます</small>
    <small v-else-if="available === false" class="text-red-500"
      >このハンドルは既に使用されています</small
    >
    <small v-else class="text-gray-400">3〜30文字の英小文字・数字・_・- が使用できます</small>
  </div>
</template>
