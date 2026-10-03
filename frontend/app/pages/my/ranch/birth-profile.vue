<script setup lang="ts">
import type { BirthConfirmation, BirthProfile, DiagnosisResult } from '~/types/ranch'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
definePageMeta({ middleware: 'auth' })
const { t } = useI18n(); useHead({ title: t('ranch.diagnosisResults.birthStyle') })
const profileApi = useBirthProfile(); const diagnosis = useDiagnosisApi(); const ranch = useRanchState(); const result = ref<DiagnosisResult | null>(null); const { handleApiError } = useErrorHandler()
const profile = ref<BirthProfile | null>(null); const confirmation = ref<BirthConfirmation | null>(null)
const loading = ref(false); const failed = ref(false); const confirmed = ref(false)
const { defineField, handleSubmit, resetForm, errors } = useForm({ validationSchema: toTypedSchema(z.object({ lastName: z.string().trim().min(1, t('ranch.birth.required')), firstName: z.string().trim().min(1, t('ranch.birth.required')), lastNameKana: z.string().trim().min(1, t('ranch.birth.required')), firstNameKana: z.string().trim().min(1, t('ranch.birth.required')), birthDate: z.date({ required_error: t('ranch.birth.required'), invalid_type_error: t('ranch.birth.required') }) })) })
const [lastName] = defineField('lastName'); const [firstName] = defineField('firstName'); const [lastNameKana] = defineField('lastNameKana'); const [firstNameKana] = defineField('firstNameKana'); const [birthDate] = defineField('birthDate')
function apply(value: BirthProfile) { profile.value = value; result.value = null; resetForm({ values: { lastName: value.lastName ?? '', firstName: value.firstName ?? '', lastNameKana: value.lastNameKana ?? '', firstNameKana: value.firstNameKana ?? '', birthDate: value.birthDate ? new Date(`${value.birthDate}T12:00:00`) : undefined } }); confirmation.value = null; confirmed.value = false }
async function load() { loading.value = true; failed.value = false; try { apply(await profileApi.get()) } catch(error) { failed.value = true; handleApiError(error, 'BirthProfileLoad') } finally { loading.value = false } }
const save = handleSubmit(async values => {
 if (!profile.value) return; loading.value = true; failed.value = false
 const d = values.birthDate; const isoDate = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`
 try { apply(await profileApi.save({ ...values, birthDate: isoDate, revision: profile.value.revision })) } catch(error) { failed.value = true; handleApiError(error, 'BirthProfileSave'); if ((error as {statusCode?:number;status?:number}).statusCode === 409 || (error as {status?:number}).status === 409) { profileApi.command.discardRejected(); await load() } } finally { loading.value = false }
})
async function create() {
 if (!profile.value || !confirmed.value) return; loading.value = true; failed.value = false
 try { confirmation.value ??= await profileApi.confirm(profile.value.revision); result.value = await diagnosis.birth(confirmation.value); await ranch.load() } catch(error) {
  const status = (error as {statusCode?:number;status?:number}).statusCode ?? (error as {status?:number}).status
  if (status === 409) { profileApi.command.discardRejected(); diagnosis.command.discardRejected(); await load() }
  failed.value = true; handleApiError(error, 'BirthProfileConfirmation')
 } finally { loading.value = false }
}
async function assign() {
 const version = ranch.state.value?.owner?.version
 if (!version || !result.value || !confirmation.value) return
 try { await ranch.act(() => ranch.api.assignment({ method: 'BIRTH_STYLE', resultId: result.value!.id, confirmationRef: confirmation.value!.confirmationRef, version })); await navigateTo('/my/ranch') } catch { failed.value = true }
}
async function retryPending() {
 loading.value = true; failed.value = false
 try {
  if (profileApi.command.pending.value) {
   const response = await profileApi.retryPending()
   if ('confirmationRef' in response) confirmation.value = response
   else apply(response)
  }
  if (diagnosis.command.pending.value) { result.value = await diagnosis.retryPending<DiagnosisResult>(); await ranch.load() }
 } catch (error) { failed.value = true; handleApiError(error, 'BirthProfileRetry') } finally { loading.value = false }
}
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.birthStyle')" back-to="/my/ranch/results" />
  <p>{{ t('ranch.birth.purpose') }}</p><p>{{ t('ranch.birth.rule') }}</p><p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
  <Button v-if="(profileApi.command.pending.value || diagnosis.command.pending.value) && !loading" class="min-h-11" :label="t('ranch.retry')" @click="retryPending" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-if="failed" @retry="load" />
  <SectionCard v-if="profile && !loading" :title="t('ranch.birth.profile')">
   <form class="grid gap-3 md:grid-cols-2" autocomplete="off" @submit.prevent="save">
    <label>{{ t('ranch.birth.lastName') }}<InputText v-model="lastName" class="w-full text-base" :disabled="!!profileApi.command.pending.value" /><span role="alert">{{ errors.lastName }}</span></label>
    <label>{{ t('ranch.birth.firstName') }}<InputText v-model="firstName" class="w-full text-base" :disabled="!!profileApi.command.pending.value" /><span role="alert">{{ errors.firstName }}</span></label>
    <label>{{ t('ranch.birth.lastNameKana') }}<InputText v-model="lastNameKana" class="w-full text-base" :disabled="!!profileApi.command.pending.value" /><span role="alert">{{ errors.lastNameKana }}</span></label>
    <label>{{ t('ranch.birth.firstNameKana') }}<InputText v-model="firstNameKana" class="w-full text-base" :disabled="!!profileApi.command.pending.value" /><span role="alert">{{ errors.firstNameKana }}</span></label>
    <label>{{ t('ranch.birth.birthDate') }}<DatePicker v-model="birthDate" date-format="yy/mm/dd" class="w-full" :disabled="!!profileApi.command.pending.value" /><span role="alert">{{ errors.birthDate }}</span></label>
    <Button type="submit" class="min-h-11" :label="t('ranch.birth.save')" :disabled="!!profileApi.command.pending.value || !!diagnosis.command.pending.value" />
   </form>
   <div class="mt-5 space-y-3">
    <p>{{ t('ranch.birth.savedProfile') }}: {{ profile.lastName }} {{ profile.firstName }} · {{ profile.lastNameKana }} {{ profile.firstNameKana }} · {{ profile.birthDate }}</p>
    <label class="flex min-h-11 items-center gap-2"><Checkbox v-model="confirmed" binary :disabled="!!profileApi.command.pending.value || !!diagnosis.command.pending.value" />{{ t('ranch.birth.confirmUse') }}</label>
    <Button class="min-h-11" :label="t('ranch.birth.create')" :disabled="!!profileApi.command.pending.value || !!diagnosis.command.pending.value || !confirmed || !profile.lastName || !profile.firstName || !profile.lastNameKana || !profile.firstNameKana || !profile.birthDate" @click="create" />
   </div>
  </SectionCard>
  <SectionCard v-if="result" :title="t('ranch.diagnosisResults.title')">
   <NuxtLink :to="`/my/ranch/results/${result.id}`" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.title') }}</NuxtLink>
   <p v-if="!result.mappingVersion">{{ t('ranch.assignment.mappingPending') }}</p>
   <Button v-if="result.mappingVersion && ranch.state.value?.dinosaur?.stage === 'EGG'" class="min-h-11" :label="t('ranch.assignment.useResult')" @click="assign" />
  </SectionCard>
 </div>
</template>
