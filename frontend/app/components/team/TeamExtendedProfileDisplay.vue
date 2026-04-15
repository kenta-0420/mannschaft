<script setup lang="ts">
import type { TeamProfileResponse, TeamOfficerResponse, TeamCustomFieldResponse } from '~/types/team'

const props = defineProps<{
  teamId: number
  isAdminOrDeputy: boolean
}>()

const { t } = useI18n()
const api = useTeamExtendedProfileApi()

const profile = ref<TeamProfileResponse | null>(null)
const officers = ref<TeamOfficerResponse[]>([])
const customFields = ref<TeamCustomFieldResponse[]>([])
const loading = ref(true)

async function loadData() {
  loading.value = true
  try {
    const [profileRes, officersRes, fieldsRes] = await Promise.all([
      api.getProfile(props.teamId),
      api.getOfficers(props.teamId, false),
      api.getCustomFields(props.teamId, false),
    ])
    profile.value = profileRes.data
    officers.value = officersRes.data.sort((a, b) => a.display_order - b.display_order)
    customFields.value = fieldsRes.data.sort((a, b) => a.display_order - b.display_order)
  } catch {
    // 表示エラーは無視（optional な拡張プロフィール）
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

const showHomepageUrl = computed(
  () => props.isAdminOrDeputy || (profile.value?.profile_visibility?.homepage_url ?? true),
)
const showEstablishedDate = computed(
  () => props.isAdminOrDeputy || (profile.value?.profile_visibility?.established_date ?? true),
)
const showPhilosophy = computed(
  () => props.isAdminOrDeputy || (profile.value?.profile_visibility?.philosophy ?? true),
)
const showOfficers = computed(
  () => props.isAdminOrDeputy || (profile.value?.profile_visibility?.officers ?? true),
)
const showCustomFields = computed(
  () => props.isAdminOrDeputy || (profile.value?.profile_visibility?.custom_fields ?? true),
)

const hasVisibleContent = computed(
  () =>
    props.isAdminOrDeputy ||
    (showHomepageUrl.value && !!profile.value?.homepage_url) ||
    (showEstablishedDate.value && !!profile.value?.established_date) ||
    (showPhilosophy.value && !!profile.value?.philosophy) ||
    (showOfficers.value && officers.value.length > 0) ||
    (showCustomFields.value && customFields.value.length > 0),
)

const formattedDate = computed(() => {
  if (!profile.value?.established_date) return ''
  const precision = profile.value.established_date_precision
  const d = profile.value.established_date
  if (precision === 'YEAR') return d.substring(0, 4) + '年'
  if (precision === 'YEAR_MONTH') {
    const parts = d.substring(0, 7).split('-')
    return `${parts[0]}年${parts[1]}月`
  }
  const parts = d.split('-')
  if (parts.length === 3) {
    return `${parts[0]}年${parts[1]}月${parts[2]}日`
  }
  return d
})
</script>

<template>
  <section
    v-if="!loading && hasVisibleContent"
    class="mt-6 border-t border-surface-200 pt-6 dark:border-surface-700"
  >
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-base font-semibold text-surface-700 dark:text-surface-200">
        {{ t('extended_profile.section_profile') }}
      </h3>
      <Button
        v-if="isAdminOrDeputy"
        :label="t('button.edit')"
        icon="pi pi-pencil"
        size="small"
        text
        @click="navigateTo(`/teams/${teamId}/extended-profile`)"
      />
    </div>

    <div v-if="showHomepageUrl && profile?.homepage_url" class="mb-3">
      <label class="text-sm font-medium text-surface-500">
        {{ t('extended_profile.homepage_url') }}
      </label>
      <p class="mt-1">
        <a
          :href="profile.homepage_url"
          target="_blank"
          rel="noopener"
          class="text-primary-600 underline"
        >
          {{ profile.homepage_url }}
        </a>
      </p>
    </div>

    <div v-if="showEstablishedDate && profile?.established_date" class="mb-3">
      <label class="text-sm font-medium text-surface-500">
        {{ t('extended_profile.established_date') }}
      </label>
      <p class="mt-1">{{ formattedDate }}</p>
    </div>

    <div v-if="showPhilosophy && profile?.philosophy" class="mb-3">
      <label class="text-sm font-medium text-surface-500">
        {{ t('extended_profile.philosophy') }}
      </label>
      <p class="mt-1 whitespace-pre-wrap">{{ profile.philosophy }}</p>
    </div>

    <div v-if="showOfficers && officers.length > 0" class="mb-3">
      <label class="mb-2 block text-sm font-medium text-surface-500">
        {{ t('extended_profile.section_officers') }}
      </label>
      <ul class="space-y-1">
        <li v-for="officer in officers" :key="officer.id" class="flex gap-2 text-sm">
          <span class="font-medium">{{ officer.name }}</span>
          <span class="text-surface-500">{{ officer.title }}</span>
        </li>
      </ul>
    </div>

    <div v-if="showCustomFields && customFields.length > 0" class="mb-3">
      <label class="mb-2 block text-sm font-medium text-surface-500">
        {{ t('extended_profile.section_custom_fields') }}
      </label>
      <dl class="space-y-1">
        <div v-for="field in customFields" :key="field.id" class="flex gap-2 text-sm">
          <dt class="font-medium text-surface-600">{{ field.label }}:</dt>
          <dd class="text-surface-700">{{ field.value }}</dd>
        </div>
      </dl>
    </div>
  </section>
</template>
