<script setup lang="ts">
import { FEATURE_DISPLAY_LABELS } from '~/constants/featureDisplay'
import type { FeatureDisplayPolicy } from '~/types/feature-display'

const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()
const { t } = useI18n()
const notification = useNotification()
const auth = useAuthStore()
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
} = useRoleAccess(props.scopeType, toRef(props, 'scopeId'), { watchScope: false })
const policy = useScopeFeatureVisibility(
  () => props.scopeType,
  () => props.scopeId,
  roleName,
)
const draft = ref<FeatureDisplayPolicy[]>([])
const saving = ref(false)
const loadError = shallowRef<unknown>()
const saveError = shallowRef<unknown>()
const editable = computed(() => roleName.value === 'ADMIN')
let generation = 0

watch(
  policy.features,
  (features) => {
    draft.value = features.map((feature) => ({ ...feature }))
  },
  { immediate: true },
)
async function load(force = false) {
  const request = generation
  loadError.value = undefined
  const result = await loadPermissions()
  if (request !== generation) return
  if (!result.ok) {
    loadError.value = result.error
    return
  }
  await policy.refresh(force)
}
watch(
  () => [props.scopeType, props.scopeId, auth.user?.id],
  () => {
    generation++
    saving.value = false
    draft.value = policy.features.value.map((feature) => ({ ...feature }))
    loadError.value = undefined
    saveError.value = undefined
    if (auth.user?.id) void load()
  },
  { immediate: true, flush: 'sync' },
)

async function save() {
  if (!editable.value || saving.value) return
  const request = generation
  saving.value = true
  saveError.value = undefined
  try {
    await policy.save(draft.value.map((feature) => ({ ...feature })))
    if (request === generation) notification.success(t('feature_display.save_success'))
  } catch (error) {
    if (request === generation) saveError.value = error
  } finally {
    if (request === generation) saving.value = false
  }
}
</script>

<template>
  <SectionCard :title="t('feature_display.title')" class="mt-6">
    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ t('feature_display.description') }}
    </p>
    <PageLoading v-if="roleLoading || policy.loading.value" class="!min-h-0 py-6" />
    <DashboardErrorState
      v-else-if="loadError || policy.error.value"
      :error="loadError || policy.error.value"
      @retry="load(true)"
    />
    <template v-else-if="policy.ready.value">
      <div
        class="grid grid-cols-[minmax(0,1fr)_5.5rem_5.5rem] gap-2 border-b border-surface-200 pb-2 text-center text-sm dark:border-surface-700"
      >
        <span class="text-left">{{ t('feature_display.feature') }}</span>
        <span>{{ t('feature_display.member') }}</span>
        <span>{{ t('feature_display.supporter') }}</span>
      </div>
      <div
        v-for="feature in draft"
        :key="feature.featureKey"
        :data-feature-key="feature.featureKey"
        class="grid grid-cols-[minmax(0,1fr)_5.5rem_5.5rem] items-center gap-2 border-b border-surface-100 py-2 dark:border-surface-700"
      >
        <span class="min-w-0 break-words text-sm">{{
          t(FEATURE_DISPLAY_LABELS[feature.featureKey] ?? feature.featureKey)
        }}</span>
        <label
          class="flex min-h-11 min-w-11 items-center justify-center"
          :for="'feature-display-' + feature.featureKey + '-member'"
        >
          <span class="sr-only">{{
            t('feature_display.member_label', {
              feature: t(FEATURE_DISPLAY_LABELS[feature.featureKey] ?? feature.featureKey),
            })
          }}</span>
          <ToggleSwitch
            v-model="feature.memberVisible"
            :input-id="'feature-display-' + feature.featureKey + '-member'"
            :disabled="!editable || saving"
          />
        </label>
        <label
          class="flex min-h-11 min-w-11 items-center justify-center"
          :for="'feature-display-' + feature.featureKey + '-supporter'"
        >
          <span class="sr-only">{{
            t('feature_display.supporter_label', {
              feature: t(FEATURE_DISPLAY_LABELS[feature.featureKey] ?? feature.featureKey),
            })
          }}</span>
          <ToggleSwitch
            v-model="feature.supporterVisible"
            :input-id="'feature-display-' + feature.featureKey + '-supporter'"
            :disabled="!editable || saving"
          />
        </label>
      </div>
      <DashboardErrorState v-if="saveError" :error="saveError" @retry="save" />
      <div v-if="editable" class="mt-4 flex justify-end">
        <Button
          :label="t('button.save')"
          icon="pi pi-save"
          :loading="saving"
          class="min-h-11"
          data-testid="feature-display-save"
          @click="save"
        />
      </div>
    </template>
  </SectionCard>
</template>
