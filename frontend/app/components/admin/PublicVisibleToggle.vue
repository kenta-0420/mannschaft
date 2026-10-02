<script setup lang="ts">
const props = defineProps<{
  postId: number
  publicVisible: boolean
}>()

const emit = defineEmits<{
  'update:publicVisible': [value: boolean]
  error: [message: string]
}>()

const { t } = useI18n()
const { patchPublicVisible } = useBlogApi()
const localVisible = ref(props.publicVisible)
const loading = ref(false)
const errorMessage = ref<string | null>(null)

watch(
  () => props.publicVisible,
  (newValue) => {
    localVisible.value = newValue
  },
)

async function handleToggle(newValue: boolean) {
  if (loading.value) return

  const previousValue = props.publicVisible
  localVisible.value = newValue
  errorMessage.value = null
  loading.value = true

  try {
    await patchPublicVisible(props.postId, newValue)
    emit('update:publicVisible', newValue)
  } catch {
    localVisible.value = previousValue
    errorMessage.value = t('public.admin.publicVisible.saveFailed')
    emit('error', errorMessage.value)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex items-center gap-2">
    <span
      :class="[
        'text-sm font-medium',
        localVisible ? 'text-green-600' : 'text-gray-400',
      ]"
    >
      {{
        localVisible
          ? t('public.admin.publicVisible.enabledLabel')
          : t('public.admin.publicVisible.disabledLabel')
      }}
    </span>

    <InputSwitch
      v-model="localVisible"
      :disabled="loading"
      :aria-label="t('public.admin.publicVisible.toggleAriaLabel')"
      :input-id="`public-visible-toggle-${postId}`"
      @update:model-value="handleToggle"
    />

    <LoadingBounce v-if="loading" />
  </div>

  <p
    v-if="errorMessage"
    class="mt-1 text-xs text-red-500"
    role="alert"
  >
    {{ errorMessage }}
  </p>
</template>
