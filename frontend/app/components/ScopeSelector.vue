<script setup lang="ts">
const scopeStore = useScopeStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

const loading = ref(false)

interface ScopeOption {
  label: string
  type: 'personal' | 'team' | 'organization'
  id: number | null
  icon: string
}

const scopeOptions = computed<ScopeOption[]>(() => {
  const options: ScopeOption[] = [
    { label: '個人', type: 'personal', id: null, icon: 'pi pi-user' },
  ]
  for (const team of teamStore.myTeams) {
    options.push({
      label: team.nickname1 || team.name,
      type: 'team',
      id: team.id,
      icon: 'pi pi-users',
    })
  }
  for (const org of orgStore.myOrganizations) {
    options.push({
      label: org.nickname1 || org.name,
      type: 'organization',
      id: org.id,
      icon: 'pi pi-building',
    })
  }
  return options
})

const selectedOption = computed(() => {
  return scopeOptions.value.find(
    o => o.type === scopeStore.current.type && o.id === scopeStore.current.id
  ) ?? scopeOptions.value[0]
})

function onScopeChange(option: ScopeOption) {
  if (option.type === 'personal') {
    scopeStore.setPersonalScope()
  } else if (option.type === 'team' && option.id) {
    scopeStore.setTeamScope(option.id, option.label)
  } else if (option.type === 'organization' && option.id) {
    scopeStore.setOrganizationScope(option.id, option.label)
  }
}

onMounted(async () => {
  loading.value = true
  await Promise.all([
    teamStore.fetchMyTeams(),
    orgStore.fetchMyOrganizations(),
  ])
  scopeStore.loadFromStorage()
  loading.value = false
})
</script>

<template>
  <Select
    :model-value="selectedOption"
    :options="scopeOptions"
    option-label="label"
    :loading="loading"
    class="w-56"
    @update:model-value="onScopeChange"
  >
    <template #value="slotProps">
      <div v-if="slotProps.value" class="flex items-center gap-2">
        <i :class="slotProps.value.icon" />
        <span class="font-medium">{{ slotProps.value.label }}</span>
      </div>
    </template>
    <template #option="slotProps">
      <div class="flex items-center gap-2">
        <i :class="slotProps.option.icon" />
        <span>{{ slotProps.option.label }}</span>
      </div>
    </template>
  </Select>
</template>
