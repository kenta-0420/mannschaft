<script setup lang="ts">
import type { CreateLegalFilingRequest, LegalFiling, LegalFilingType } from '~/types/succession'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
const { t } = useI18n()
const toast = useToast()
const { listByOrganization, createLegalFiling, buildEvidencePackage, getEvidenceDownloadUrl } = useLegalFilingApi()
const { formatDateTime: _fmt } = useDatetime()

const filings = ref<LegalFiling[]>([])
const loading = ref(false)
const buildingId = ref<string | null>(null)
const downloadingId = ref<string | null>(null)

// 起票ダイアログ
const showCreateDialog = ref(false)
const submittingCreate = ref(false)
const createForm = ref<CreateLegalFilingRequest>({
  residentRegistryId: 0,
  dwellingUnitId: 0,
  filingType: 'ABSENTEE_PROPERTY_MANAGER',
  note: '',
})

const filingTypeOptions: Array<{ label: string, value: LegalFilingType }> = [
  { label: t('succession.legalFilings.absenteePropertyManager'), value: 'ABSENTEE_PROPERTY_MANAGER' },
  { label: t('succession.legalFilings.inheritanceLiquidator'), value: 'INHERITANCE_LIQUIDATOR' },
]

function formatDateTime(iso?: string | null): string {
  return _fmt(iso) || '-'
}

function filingTypeLabel(type: LegalFilingType): string {
  return type === 'ABSENTEE_PROPERTY_MANAGER'
    ? t('succession.legalFilings.absenteePropertyManager')
    : t('succession.legalFilings.inheritanceLiquidator')
}

async function fetchFilings() {
  loading.value = true
  try {
    const res = await listByOrganization(orgSlug.value)
    filings.value = res.data
  }
  catch (e) {
    console.error('法的手続き一覧取得エラー:', e)
    toast.add({ severity: 'error', summary: t('succession.legalFilings.fetchError'), life: 4000 })
  }
  finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = {
    residentRegistryId: 0,
    dwellingUnitId: 0,
    filingType: 'ABSENTEE_PROPERTY_MANAGER',
    note: '',
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  submittingCreate.value = true
  try {
    await createLegalFiling(orgSlug.value, createForm.value)
    showCreateDialog.value = false
    toast.add({ severity: 'success', summary: t('succession.legalFilings.createSuccess'), life: 3000 })
    await fetchFilings()
  }
  catch (e) {
    console.error('法的手続き起票エラー:', e)
    toast.add({ severity: 'error', summary: t('succession.legalFilings.createError'), life: 4000 })
  }
  finally {
    submittingCreate.value = false
  }
}

async function handleBuildEvidence(filing: LegalFiling) {
  buildingId.value = filing.id
  try {
    await buildEvidencePackage(orgSlug.value, filing.id)
    toast.add({ severity: 'success', summary: t('succession.legalFilings.evidenceBuildSuccess'), life: 3000 })
    await fetchFilings()
  }
  catch (e) {
    console.error('証拠 ZIP 生成エラー:', e)
    toast.add({ severity: 'error', summary: t('succession.legalFilings.evidenceBuildError'), life: 4000 })
  }
  finally {
    buildingId.value = null
  }
}

async function handleDownloadEvidence(filing: LegalFiling) {
  downloadingId.value = filing.id
  try {
    const res = await getEvidenceDownloadUrl(orgSlug.value, filing.id)
    if (typeof window !== 'undefined') {
      window.open(res.data.downloadUrl, '_blank', 'noopener,noreferrer')
    }
  }
  catch (e) {
    console.error('証拠 ZIP ダウンロード URL 取得エラー:', e)
    toast.add({ severity: 'error', summary: t('succession.legalFilings.downloadError'), life: 4000 })
  }
  finally {
    downloadingId.value = null
  }
}

onMounted(async () => {
  await fetchFilings()
})
</script>

<template>
  <div class="flex flex-col gap-6 p-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">
        {{ t('succession.legalFilings.title') }}
      </h1>
      <Button
        :label="t('succession.legalFilings.create')"
        icon="pi pi-plus"
        @click="openCreateDialog"
      />
    </div>

    <Message severity="warn" :closable="false">
      {{ t('succession.legalFilings.warningLegalCheck') }}
    </Message>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <LoadingBounce />
    </div>
    <DataTable
      v-else
      :value="filings"
      class="w-full"
      data-key="id"
    >
      <template #empty>
        <div class="text-center py-8 text-gray-400">
          {{ t('succession.legalFilings.empty') }}
        </div>
      </template>
      <Column :header="t('succession.legalFilings.filingType')" style="width: 14rem">
        <template #body="{ data }">
          <Tag :value="filingTypeLabel(data.filingType)" severity="info" />
        </template>
      </Column>
      <Column field="residentRegistryId" :header="t('succession.legalFilings.residentRegistryId')" style="width: 9rem" />
      <Column field="dwellingUnitId" :header="t('succession.legalFilings.dwellingUnitId')" style="width: 8rem" />
      <Column :header="t('succession.legalFilings.createdAt')" style="width: 12rem">
        <template #body="{ data }">
          {{ formatDateTime(data.createdAt) }}
        </template>
      </Column>
      <Column :header="t('succession.legalFilings.evidenceBuiltAt')" style="width: 12rem">
        <template #body="{ data }">
          <span v-if="data.evidenceBuiltAt">{{ formatDateTime(data.evidenceBuiltAt) }}</span>
          <span v-else class="text-gray-400">{{ t('succession.legalFilings.evidenceNotReady') }}</span>
        </template>
      </Column>
      <Column :header="t('succession.legalFilings.action')" style="width: 18rem">
        <template #body="{ data }">
          <div class="flex gap-2">
            <Button
              v-if="!data.evidencePackageS3Key"
              size="small"
              severity="secondary"
              :loading="buildingId === data.id"
              :label="t('succession.legalFilings.buildEvidence')"
              icon="pi pi-cog"
              @click="handleBuildEvidence(data)"
            />
            <Button
              v-else
              size="small"
              :loading="downloadingId === data.id"
              :label="t('succession.legalFilings.downloadEvidence')"
              icon="pi pi-download"
              @click="handleDownloadEvidence(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showCreateDialog"
      modal
      :header="t('succession.legalFilings.create')"
      :style="{ width: '32rem' }"
    >
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('succession.legalFilings.filingType') }}</label>
          <Dropdown
            v-model="createForm.filingType"
            :options="filingTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('succession.legalFilings.residentRegistryId') }}</label>
          <InputNumber
            v-model="createForm.residentRegistryId"
            :use-grouping="false"
            :min="1"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('succession.legalFilings.dwellingUnitId') }}</label>
          <InputNumber
            v-model="createForm.dwellingUnitId"
            :use-grouping="false"
            :min="1"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('succession.legalFilings.note') }}</label>
          <Textarea
            v-model="createForm.note"
            rows="3"
            class="w-full"
            :maxlength="1000"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('succession.legalFilings.cancel')"
          severity="secondary"
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('succession.legalFilings.submit')"
          :loading="submittingCreate"
          :disabled="!createForm.residentRegistryId || !createForm.dwellingUnitId"
          @click="handleCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
