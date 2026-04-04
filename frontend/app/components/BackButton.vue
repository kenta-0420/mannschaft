<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    to?: string
    label?: string
  }>(),
  {
    to: undefined,
    label: '戻る',
  },
)

const router = useRouter()

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
    <i class="pi pi-arrow-left text-sm" />{{ label }}
  </NuxtLink>
  <button
    v-else
    class="mb-4 inline-flex items-center gap-1.5 text-base font-medium text-primary hover:underline"
    @click="goBack"
  >
    <i class="pi pi-arrow-left text-sm" />{{ label }}
  </button>
</template>
