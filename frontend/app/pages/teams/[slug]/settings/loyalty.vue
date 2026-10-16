<script setup lang="ts">
import type { LoyaltySettings, LoyaltyAudience, SelfAwardMode } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'] })
const context = useTeamLoyaltyContext()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const confirm = useConfirm()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const settings = ref<LoyaltySettings | null>(null)
const enabled = ref(false)
const audience = ref<LoyaltyAudience>('MEMBERS_AND_ABOVE')
const mode = ref<SelfAwardMode>('STAFF_APPROVAL')
const loading = ref(true)
const saving = ref(false)
const error = shallowRef<unknown>(null)
async function load() {
  loading.value = true; error.value = null
  try {
    await context.load()
    if (!context.access.isScopeAdmin.value) throw { statusCode: 403 }
    settings.value = await api.settings(context.teamId.value!)
    enabled.value = settings.value.isEnabled; audience.value = settings.value.targetAudience; mode.value = settings.value.selfAwardMode
  } catch (e) { error.value = e; handleApiError(e) }
  finally { loading.value = false }
}
async function save() {
  if (!settings.value || saving.value) return
  saving.value = true
  try {
    await api.saveSettings(settings.value.teamId, { expectedVersion: settings.value.version, isEnabled: enabled.value, targetAudience: audience.value, selfAwardMode: mode.value })
    notification.success(t('loyalty.saved')); await load()
  } catch (e) { handleApiError(e) }
  finally { saving.value = false }
}
function submit() {
  if (mode.value === 'AUTO') confirm.require({ header: t('loyalty.mode.auto'), message: t('loyalty.mode.autoWarning'), acceptLabel: t('loyalty.save'), rejectLabel: t('loyalty.cancel'), accept: save })
  else void save()
}
useHead({ title: () => t('loyalty.settings.title') })
onMounted(load)
</script>
<template>
  <main class="mx-auto max-w-2xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.settings.title')" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <template v-else-if="settings">
      <NuxtLink :to="`/teams/${context.slug.value}/loyalty`" class="inline-flex min-h-11 items-center">{{ t('loyalty.scan.title') }}</NuxtLink>
      <SectionCard :title="context.teamName.value">
        <form class="space-y-4" @submit.prevent="submit">
          <label class="flex min-h-11 items-center gap-3"><Checkbox v-model="enabled" binary />{{ t('loyalty.settings.enabled') }}</label>
          <label class="block">{{ t('loyalty.audience.title') }}<Select v-model="audience" class="mt-1 w-full" :options="[{label:t('loyalty.audience.allAuthenticated'),value:'ALL_AUTHENTICATED'},{label:t('loyalty.audience.supportersAndAbove'),value:'SUPPORTERS_AND_ABOVE'},{label:t('loyalty.audience.membersAndAbove'),value:'MEMBERS_AND_ABOVE'}]" option-label="label" option-value="value" /></label>
          <label class="block">{{ t('loyalty.mode.title') }}<Select v-model="mode" class="mt-1 w-full" :options="[{label:t('loyalty.mode.staffApproval'),value:'STAFF_APPROVAL'},{label:t('loyalty.mode.auto'),value:'AUTO'}]" option-label="label" option-value="value" /></label>
          <p v-if="mode === 'AUTO'" role="alert">{{ t('loyalty.mode.autoWarning') }}</p>
          <Button type="submit" class="min-h-11" :label="t('loyalty.save')" :loading="saving" />
        </form>
      </SectionCard>
      <template v-if="settings.id">
        <LoyaltyRuleEditor :key="settings.rules[0]?.version ?? 'new'" :team-id="settings.teamId" :rule="settings.rules[0]" @saved="load" />
        <LoyaltyRewardEditor v-for="reward in settings.rewards" :key="`${reward.id}-${reward.version}`" :team-id="settings.teamId" :reward="reward" @saved="load" />
        <LoyaltyRewardEditor v-if="settings.rewards.length < 20" :team-id="settings.teamId" @saved="load" />
        <LoyaltyReceptionSettings :team-id="settings.teamId" :team-name="context.teamName.value" :mode="settings.selfAwardMode" />
      </template>
    </template>
  </main>
</template>
