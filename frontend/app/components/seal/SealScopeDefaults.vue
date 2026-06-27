<script setup lang="ts">
import type { ScopeDefault, SealVariant } from '~/types/seal'

const props = defineProps<{
  defaults: ScopeDefault[]
}>()

const emit = defineEmits<{
  save: [defaults: ScopeDefault[]]
}>()

const { t } = useI18n()
const api = useApi()

const localDefaults = ref<ScopeDefault[]>([...props.defaults])
const showAddDialog = ref(false)

type AddScopeType = 'TEAM' | 'ORGANIZATION'

interface ScopeTarget {
  id: number
  name: string
}

const newScopeType = ref<AddScopeType>('TEAM')
const newScopeId = ref<number | null>(null)
const newVariant = ref<SealVariant>('LAST_NAME')

const teams = ref<ScopeTarget[]>([])
const organizations = ref<ScopeTarget[]>([])
const loadingTargets = ref(false)

const variantOptions = computed(() => [
  { label: t('settings.seal.scope_defaults.variant_last_name'), value: 'LAST_NAME' as SealVariant },
  { label: t('settings.seal.scope_defaults.variant_full_name'), value: 'FULL_NAME' as SealVariant },
  { label: t('settings.seal.scope_defaults.variant_first_name'), value: 'FIRST_NAME' as SealVariant },
])

const scopeTypeOptions = computed(() => [
  { label: t('settings.seal.scope_defaults.scope_type_team'), value: 'TEAM' as AddScopeType },
  { label: t('settings.seal.scope_defaults.scope_type_org'), value: 'ORGANIZATION' as AddScopeType },
])

const scopeLabel = (d: ScopeDefault): string => {
  if (d.scopeType === 'DEFAULT') return t('settings.seal.scope_defaults.default_label')
  return (
    d.scopeName ??
    (d.scopeType === 'TEAM'
      ? t('settings.seal.scope_defaults.scope_type_team')
      : t('settings.seal.scope_defaults.scope_type_org'))
  )
}

const usedTeamIds = computed(() =>
  localDefaults.value.filter((d) => d.scopeType === 'TEAM').map((d) => d.scopeId),
)
const usedOrgIds = computed(() =>
  localDefaults.value.filter((d) => d.scopeType === 'ORGANIZATION').map((d) => d.scopeId),
)

const availableTargets = computed<ScopeTarget[]>(() => {
  if (newScopeType.value === 'TEAM') {
    return teams.value.filter((tm) => !usedTeamIds.value.includes(tm.id))
  }
  return organizations.value.filter((org) => !usedOrgIds.value.includes(org.id))
})

async function openAddDialog() {
  newScopeType.value = 'TEAM'
  newScopeId.value = null
  newVariant.value = 'LAST_NAME'
  showAddDialog.value = true
  if (teams.value.length === 0 || organizations.value.length === 0) {
    loadingTargets.value = true
    try {
      const [teamsRes, orgsRes] = await Promise.all([
        teams.value.length === 0
          ? api<{ data: Array<{ id: number; name: string }> }>('/api/v1/me/teams')
          : Promise.resolve({ data: teams.value }),
        organizations.value.length === 0
          ? api<{ data: Array<{ id: number; name: string }> }>('/api/v1/me/organizations')
          : Promise.resolve({ data: organizations.value }),
      ])
      if (teams.value.length === 0) {
        teams.value = teamsRes.data.map((tm) => ({ id: tm.id, name: tm.name }))
      }
      if (organizations.value.length === 0) {
        organizations.value = orgsRes.data.map((org) => ({ id: org.id, name: org.name }))
      }
    } finally {
      loadingTargets.value = false
    }
  }
}

function addScope() {
  if (newScopeId.value == null) return
  const targetList = newScopeType.value === 'TEAM' ? teams.value : organizations.value
  const target = targetList.find((tm) => tm.id === newScopeId.value)
  localDefaults.value.push({
    scopeType: newScopeType.value,
    scopeId: newScopeId.value,
    scopeName: target?.name ?? null,
    variant: newVariant.value,
  })
  showAddDialog.value = false
  emit('save', localDefaults.value)
}

function removeScope(index: number) {
  localDefaults.value.splice(index, 1)
  emit('save', localDefaults.value)
}

function handleSave() {
  emit('save', localDefaults.value)
}

watch(
  () => props.defaults,
  (newVal) => {
    localDefaults.value = [...newVal]
  },
)
</script>

<template>
  <div>
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-sm font-medium">
        {{ t('settings.seal.scope_defaults.section_title') }}
      </h3>
      <Button
        :label="t('settings.seal.scope_defaults.add_button')"
        icon="pi pi-plus"
        size="small"
        text
        @click="openAddDialog"
      />
    </div>

    <div class="space-y-3">
      <div
        v-for="(d, index) in localDefaults"
        :key="`${d.scopeType}-${d.scopeId}`"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <span class="min-w-28 text-sm font-medium">{{ scopeLabel(d) }}</span>
        <Select
          v-model="localDefaults[index]!.variant"
          :options="variantOptions"
          option-label="label"
          option-value="value"
          class="flex-1"
        />
        <Button
          v-if="d.scopeType !== 'DEFAULT'"
          icon="pi pi-trash"
          text
          severity="danger"
          size="small"
          :aria-label="t('settings.seal.scope_defaults.delete_tooltip')"
          @click="removeScope(index)"
        />
      </div>
    </div>

    <div class="mt-4 flex justify-end">
      <Button
        :label="t('settings.seal.scope_defaults.save_button')"
        icon="pi pi-check"
        @click="handleSave"
      />
    </div>

    <!-- スコープ追加ダイアログ -->
    <Dialog
      v-model:visible="showAddDialog"
      modal
      :header="t('settings.seal.scope_defaults.add_dialog_title')"
      class="w-full max-w-md"
    >
      <div class="space-y-4 py-2">
        <!-- スコープ種別 -->
        <div>
          <label class="mb-2 block text-sm font-medium">
            {{ t('settings.seal.scope_defaults.scope_type_label') }}
          </label>
          <SelectButton
            v-model="newScopeType"
            :options="scopeTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            @change="newScopeId = null"
          />
        </div>

        <!-- チーム / 組織 選択 -->
        <div>
          <label class="mb-2 block text-sm font-medium">
            {{ t('settings.seal.scope_defaults.scope_target_label') }}
          </label>
          <Select
            v-model="newScopeId"
            :options="availableTargets"
            option-label="name"
            option-value="id"
            class="w-full"
            :loading="loadingTargets"
            :placeholder="t('settings.seal.scope_defaults.scope_target_placeholder')"
            :empty-message="
              newScopeType === 'TEAM'
                ? t('settings.seal.scope_defaults.no_teams')
                : t('settings.seal.scope_defaults.no_orgs')
            "
          />
        </div>

        <!-- 印鑑バリアント -->
        <div>
          <label class="mb-2 block text-sm font-medium">
            {{ t('settings.seal.scope_defaults.variant_label') }}
          </label>
          <Select
            v-model="newVariant"
            :options="variantOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('settings.seal.scope_defaults.cancel_button')"
          icon="pi pi-times"
          text
          @click="showAddDialog = false"
        />
        <Button
          :label="t('settings.seal.scope_defaults.add_scope_button')"
          icon="pi pi-check"
          :disabled="newScopeId == null"
          @click="addScope"
        />
      </template>
    </Dialog>
  </div>
</template>
