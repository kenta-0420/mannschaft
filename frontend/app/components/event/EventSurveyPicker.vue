<script setup lang="ts">
const props = defineProps<{
  modelValue: number | null
  label: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const useNone = ref(props.modelValue === null)
const surveyId = ref<number | null>(props.modelValue)

watch(
  () => props.modelValue,
  (val) => {
    useNone.value = val === null
    surveyId.value = val
  },
)

watch(useNone, (val) => {
  if (val) {
    surveyId.value = null
    emit('update:modelValue', null)
  }
})

watch(surveyId, (val) => {
  if (!useNone.value) {
    emit('update:modelValue', val)
  }
})
</script>

<template>
  <div class="flex flex-col gap-2">
    <label class="mb-1 block text-sm font-medium">{{ label }}</label>
    <div class="flex items-center gap-3">
      <div class="flex items-center gap-2">
        <Checkbox v-model="useNone" :binary="true" :input-id="`none-${label}`" />
        <label :for="`none-${label}`" class="text-sm">{{ $t('event.survey.none') }}</label>
      </div>
      <InputNumber
        v-if="!useNone"
        v-model="surveyId"
        :placeholder="$t('event.survey.surveyId')"
        :min="1"
        class="w-48"
      />
    </div>
  </div>
</template>
