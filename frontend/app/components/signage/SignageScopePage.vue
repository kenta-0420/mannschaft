<script setup lang="ts">
const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  slug: string
}>()

const teamApi = useTeamApi()
const organizationApi = useOrganizationApi()
const scopeId = ref<string | null>(null)
const loading = ref(true)
const loadError = shallowRef<unknown>(null)
let requestVersion = 0

async function loadScope() {
  const version = ++requestVersion
  const { scopeType, slug } = props
  scopeId.value = null
  loading.value = true
  loadError.value = null
  try {
    const response = scopeType === 'TEAM'
      ? await teamApi.getTeam(slug)
      : await organizationApi.getOrganization(slug)
    const numericId = response.data.numericId
    if (typeof numericId !== 'number' || !Number.isSafeInteger(numericId) || numericId <= 0) {
      throw new Error('signage_scope_numeric_id_missing')
    }
    if (version === requestVersion) scopeId.value = String(numericId)
  } catch (error) {
    if (version === requestVersion) loadError.value = error
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

// 切替直後から旧スコープを描画せず、前のリクエストの遅延応答も採用しない。
watch(() => [props.scopeType, props.slug], loadScope, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { requestVersion++ })
</script>

<template>
  <div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="loadScope" />
    <SignageManager
      v-else-if="scopeId"
      :key="`${scopeType}:${scopeId}`"
      :scope-type="scopeType"
      :scope-id="scopeId"
    />
  </div>
</template>
