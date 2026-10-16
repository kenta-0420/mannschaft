<script setup lang="ts">
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import type { CheckinLocation } from '~/types/member-card'
import type { SelfAwardMode } from '~/types/team-loyalty'
const props = defineProps<{ teamId: number; teamName: string; mode: SelfAwardMode }>()
const { t } = useI18n()
const api = useMemberCardApi()
const confirm = useConfirm()
const { handleApiError } = useErrorHandler()
const locations = ref<CheckinLocation[]>([])
const selected = ref<number | null>(null)
const qrUrl = ref('')
const loading = ref(true)
const error = shallowRef<unknown>(null)
const { defineField, handleSubmit, errors, isSubmitting, resetForm } = useForm({ validationSchema: toTypedSchema(z.object({ name: z.string().trim().min(1).max(100) })), initialValues: { name: '' } })
const [name] = defineField('name')
async function load() {
  loading.value = true; error.value = null; qrUrl.value = ''
  try { locations.value = await api.listLocations(String(props.teamId)) }
  catch (e) { error.value = e; handleApiError(e) }
  finally { loading.value = false }
}
const create = handleSubmit(async value => {
  try {
    const location = await api.createLocation(String(props.teamId), { name: value.name, autoCompleteReservation: false })
    selected.value = location.id; resetForm(); await load()
  } catch (e) { handleApiError(e) }
})
async function preview() {
  if (!selected.value) return
  qrUrl.value = ''
  try {
    const data = await api.getLocationQr(String(props.teamId), selected.value)
    qrUrl.value = `${window.location.origin}/my/team-points/receive#locationToken=${encodeURIComponent(data.qrToken)}`
  } catch (e) { handleApiError(e) }
}
function stop() {
  const location = locations.value.find(item => item.id === selected.value)
  if (!location) return
  confirm.require({ message: t('loyalty.reception.sharedLocationStopWarning'), header: t('loyalty.reception.stop'),
    acceptLabel: t('loyalty.reception.stop'), rejectLabel: t('loyalty.cancel'), accept: async () => {
      try {
        await api.updateLocation(String(props.teamId), location.id, { name: location.name, isActive: false, autoCompleteReservation: location.autoCompleteReservation })
        await load()
      } catch (e) { handleApiError(e) }
    } })
}
function print() { window.print() }
onMounted(load)
</script>
<template>
  <SectionCard :title="t('loyalty.reception.title')" class="loyalty-reception">
    <div class="print:hidden">
      <PageLoading v-if="loading" />
      <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
      <template v-else>
        <label class="block">{{ t('loyalty.reception.location') }}<Select v-model="selected" class="my-2 w-full" :options="locations.filter(item => item.isActive)" option-label="name" option-value="id" @change="qrUrl = ''" /></label>
        <div class="flex flex-wrap gap-3"><Button class="min-h-11" :label="t('loyalty.reception.preview')" :disabled="!selected" @click="preview" /><Button class="min-h-11" severity="danger" :label="t('loyalty.reception.stop')" :disabled="!selected" @click="stop" /></div>
        <form class="mt-4 space-y-3" @submit.prevent="create">
          <label class="block">{{ t('loyalty.reception.newLocation') }}<InputText v-model="name" class="mt-1 w-full text-base" /></label>
          <p v-if="errors.name" role="alert">{{ errors.name }}</p>
          <Button type="submit" class="min-h-11" :label="t('loyalty.reception.create')" :loading="isSubmitting" />
        </form>
      </template>
    </div>
    <div v-if="qrUrl" class="mt-4 space-y-3 text-center" data-testid="loyalty-reception-print">
      <h3 class="text-xl font-semibold">{{ teamName }} · {{ t('loyalty.reception.printTitle') }}</h3>
      <QrCodeImage :value="qrUrl" :size="280" class="mx-auto max-w-full" :aria-label="t('loyalty.reception.printTitle')" />
      <p>{{ t(`loyalty.mode.${mode === 'AUTO' ? 'auto' : 'staffApproval'}`) }}</p>
      <p>{{ t('loyalty.reception.printInstructions') }}</p>
      <p v-if="mode === 'STAFF_APPROVAL'">{{ t('loyalty.requests.showStaff') }}</p>
      <p v-else>{{ t('loyalty.mode.autoWarning') }}</p>
      <Button class="min-h-11 print:hidden" :label="t('loyalty.reception.print')" icon="pi pi-print" @click="print" />
    </div>
  </SectionCard>
</template>
<style>
@media print {
  body * { visibility: hidden; }
  [data-testid='loyalty-reception-print'], [data-testid='loyalty-reception-print'] * { visibility: visible; }
  [data-testid='loyalty-reception-print'] { position: absolute; top: 0; left: 0; width: 100%; }
}
</style>
