<script setup lang="ts">
import type { MemberResponse } from '~/types/member'

const props = defineProps<{ slug: string; adminOnly?: boolean; label: string }>()
const model = defineModel<MemberResponse | null>({ default: null })
const { t } = useI18n()
const api = useOrganizationApi()
const members = ref<MemberResponse[]>([])
const page = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const failed = ref(false)
let request = 0
const candidates = computed(() => members.value.filter(m => m.displayName && (!props.adminOnly || m.roleName === 'ADMIN')))

async function load() {
  const current = ++request
  loading.value = true
  failed.value = false
  members.value = []
  try {
    const result = await api.getMembers(props.slug, { page: page.value, size: 20 })
    if (current !== request) return
    members.value = result.data
    totalPages.value = result.meta.totalPages
  }
  catch {
    if (current === request) failed.value = true
  }
  finally {
    if (current === request) loading.value = false
  }
}
watch(() => props.slug, () => { page.value = 0; model.value = null; void load() })
watch(page, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <fieldset class="min-w-0 space-y-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700">
    <legend class="px-1 font-medium">{{ label }}</legend>
    <p v-if="model" class="break-words">{{ model.displayName }}</p>
    <p v-if="loading" role="status">{{ t('proxy.management.loading') }}</p>
    <div v-else-if="failed" role="alert">
      <p>{{ t('proxy.management.loadFailed') }}</p>
      <Button :label="t('proxy.management.retry')" class="mt-2 min-h-11" @click="load" />
    </div>
    <template v-else>
      <Select v-model="model" :options="candidates" option-label="displayName" :placeholder="t('proxy.management.chooseMember')" :aria-label="label" class="min-h-11 w-full" show-clear />
      <p v-if="!candidates.length" class="text-sm">{{ t('proxy.management.noCandidates') }}</p>
      <div class="flex flex-wrap items-center gap-2">
        <Button :label="t('proxy.management.previous')" class="min-h-11" outlined :disabled="page === 0" @click="page--" />
        <span>{{ page + 1 }} / {{ Math.max(totalPages, 1) }}</span>
        <Button :label="t('proxy.management.next')" class="min-h-11" outlined :disabled="page + 1 >= totalPages" @click="page++" />
      </div>
    </template>
  </fieldset>
</template>
