<script setup lang="ts">
const props = defineProps<{
  to?: string
  label?: string
}>()

const { t } = useI18n()
const router = useRouter()

// label 未指定なら i18n の共通「戻る」キーにフォールバックする
// （キーは common.json ルートの button.back。プレフィックス無しが正しい解決パス）
const displayLabel = computed(() => props.label ?? t('button.back'))

function goBack() {
  if (props.to) {
    navigateTo(props.to)
  } else {
    router.back()
  }
}
</script>

<template>
  <NuxtLink
    v-if="to"
    :to="to"
    class="mb-4 inline-flex items-center gap-1.5 text-base font-medium text-primary hover:underline"
  >
    <i class="pi pi-arrow-left text-sm" />{{ displayLabel }}
  </NuxtLink>
  <button
    v-else
    class="mb-4 inline-flex items-center gap-1.5 text-base font-medium text-primary hover:underline"
    @click="goBack"
  >
    <i class="pi pi-arrow-left text-sm" />{{ displayLabel }}
  </button>
</template>
