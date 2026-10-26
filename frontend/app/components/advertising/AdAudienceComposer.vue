<script setup lang="ts">
// F09.17 チップ式オーディエンスセグメント編集 UI
// 設計書 §9.2 で名指し
import type {
  AdMessagingCampaignAudienceSegmentRequest,
  AdSegmentInclusionMode,
  AdSegmentType,
  AdSegmentValue,
} from '~/types/adMessagingCampaign'

const { t } = useI18n()

const props = defineProps<{
  modelValue: AdMessagingCampaignAudienceSegmentRequest[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: AdMessagingCampaignAudienceSegmentRequest[]): void
}>()

const segmentTypes: AdSegmentType[] = [
  'AGE_RANGE',
  'GENDER',
  'REGION_PREFECTURE',
  'REGION_CITY',
  'INTEREST_TAG',
  'ORG_TYPE',
  'LOCALE',
  'DEVICE',
]

// 新規追加用バッファ
const draftType = ref<AdSegmentType | null>(null)
const draftInclusion = ref<AdSegmentInclusionMode>('INCLUDE')
const draftAgeMin = ref<number | null>(null)
const draftAgeMax = ref<number | null>(null)
const draftGenders = ref<string[]>([])
const draftPrefectureCodes = ref<string>('')
const draftCityCodes = ref<string>('')
const draftInterestTags = ref<string>('')
const draftOrgTypes = ref<string>('')
const draftLocales = ref<string[]>([])
const draftDevices = ref<string[]>([])

const genderOptions = [
  { value: 'MALE', label: 'MALE' },
  { value: 'FEMALE', label: 'FEMALE' },
  { value: 'OTHER', label: 'OTHER' },
]
const localeOptions = ['ja', 'en', 'zh-CN', 'zh-TW', 'ko', 'pt-BR']
const deviceOptions = ['IOS', 'ANDROID', 'WEB']

function resetDraft() {
  draftType.value = null
  draftInclusion.value = 'INCLUDE'
  draftAgeMin.value = null
  draftAgeMax.value = null
  draftGenders.value = []
  draftPrefectureCodes.value = ''
  draftCityCodes.value = ''
  draftInterestTags.value = ''
  draftOrgTypes.value = ''
  draftLocales.value = []
  draftDevices.value = []
}

function splitCsv(str: string): string[] {
  return str
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

function buildSegmentValue(type: AdSegmentType): AdSegmentValue | null {
  switch (type) {
    case 'AGE_RANGE':
      if (draftAgeMin.value == null || draftAgeMax.value == null) return null
      return { min: draftAgeMin.value, max: draftAgeMax.value }
    case 'GENDER':
      if (draftGenders.value.length === 0) return null
      return { genders: draftGenders.value }
    case 'REGION_PREFECTURE': {
      const codes = splitCsv(draftPrefectureCodes.value)
      if (codes.length === 0) return null
      return { codes }
    }
    case 'REGION_CITY': {
      const codes = splitCsv(draftCityCodes.value)
      if (codes.length === 0) return null
      return { codes }
    }
    case 'INTEREST_TAG': {
      const tags = splitCsv(draftInterestTags.value)
      if (tags.length === 0) return null
      return { tags }
    }
    case 'ORG_TYPE': {
      const types = splitCsv(draftOrgTypes.value)
      if (types.length === 0) return null
      return { types }
    }
    case 'LOCALE':
      if (draftLocales.value.length === 0) return null
      return { locales: draftLocales.value }
    case 'DEVICE':
      if (draftDevices.value.length === 0) return null
      return { devices: draftDevices.value }
  }
}

function addSegment() {
  if (!draftType.value) return
  const value = buildSegmentValue(draftType.value)
  if (!value) return
  const next: AdMessagingCampaignAudienceSegmentRequest[] = [
    ...props.modelValue,
    {
      segmentType: draftType.value,
      segmentValue: value,
      inclusionMode: draftInclusion.value,
    },
  ]
  emit('update:modelValue', next)
  resetDraft()
}

function removeSegment(index: number) {
  const next = props.modelValue.filter((_, i) => i !== index)
  emit('update:modelValue', next)
}

function describeSegment(seg: AdMessagingCampaignAudienceSegmentRequest): string {
  const v = seg.segmentValue as AdSegmentValue
  switch (seg.segmentType) {
    case 'AGE_RANGE':
      return `${v.min ?? '?'}-${v.max ?? '?'}`
    case 'GENDER':
      return (v.genders as string[] | undefined)?.join(', ') ?? ''
    case 'REGION_PREFECTURE':
    case 'REGION_CITY':
      return (v.codes as string[] | undefined)?.join(', ') ?? ''
    case 'INTEREST_TAG':
      return (v.tags as string[] | undefined)?.join(', ') ?? ''
    case 'ORG_TYPE':
      return (v.types as string[] | undefined)?.join(', ') ?? ''
    case 'LOCALE':
      return (v.locales as string[] | undefined)?.join(', ') ?? ''
    case 'DEVICE':
      return (v.devices as string[] | undefined)?.join(', ') ?? ''
    default:
      return JSON.stringify(v)
  }
}
</script>

<template>
  <div class="space-y-4">
    <!-- 既存セグメントチップ -->
    <div v-if="modelValue.length > 0" class="flex flex-wrap gap-2">
      <div
        v-for="(seg, idx) in modelValue"
        :key="idx"
        class="flex items-center gap-2 rounded-full border border-surface-300 bg-surface-50 px-3 py-1 text-sm dark:border-surface-600 dark:bg-surface-700"
      >
        <Tag
          :value="t(`advertising.inclusion_mode.${seg.inclusionMode.toLowerCase()}`)"
          :severity="seg.inclusionMode === 'INCLUDE' ? 'success' : 'danger'"
        />
        <span class="font-medium">{{ t(`advertising.segment_type.${seg.segmentType.toLowerCase()}`) }}</span>
        <span class="text-surface-600 dark:text-surface-300">{{ describeSegment(seg) }}</span>
        <Button
          icon="pi pi-times"
          severity="secondary"
          text
          rounded
          size="small"
          :aria-label="t('advertising.advertiser_crud.segment_form.remove')"
          @click="removeSegment(idx)"
        />
      </div>
    </div>

    <!-- 新規追加フォーム -->
    <div class="rounded-lg border border-surface-300 p-4 dark:border-surface-600">
      <p class="mb-2 font-semibold">{{ t('advertising.actions.add_segment') }}</p>

      <div class="mb-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.select_type') }}</label>
          <Select
            v-model="draftType"
            :options="segmentTypes"
            class="w-full"
          >
            <template #value="{ value }">
              <span v-if="value">{{ t(`advertising.segment_type.${(value as string).toLowerCase()}`) }}</span>
            </template>
            <template #option="{ option }">
              {{ t(`advertising.segment_type.${(option as string).toLowerCase()}`) }}
            </template>
          </Select>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.inclusion_mode') }}</label>
          <Select
            v-model="draftInclusion"
            :options="['INCLUDE', 'EXCLUDE']"
            class="w-full"
          >
            <template #value="{ value }">
              {{ value ? t(`advertising.inclusion_mode.${(value as string).toLowerCase()}`) : '' }}
            </template>
            <template #option="{ option }">
              {{ t(`advertising.inclusion_mode.${(option as string).toLowerCase()}`) }}
            </template>
          </Select>
        </div>
      </div>

      <!-- type別フォーム -->
      <div v-if="draftType === 'AGE_RANGE'" class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.age_min') }}</label>
          <InputNumber v-model="draftAgeMin" :min="0" :max="120" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.age_max') }}</label>
          <InputNumber v-model="draftAgeMax" :min="0" :max="120" class="w-full" />
        </div>
      </div>
      <div v-else-if="draftType === 'GENDER'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.genders') }}</label>
        <MultiSelect
          v-model="draftGenders"
          :options="genderOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div v-else-if="draftType === 'REGION_PREFECTURE'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.prefecture_codes') }}</label>
        <InputText v-model="draftPrefectureCodes" class="w-full" placeholder="13, 14" />
      </div>
      <div v-else-if="draftType === 'REGION_CITY'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.city_codes') }}</label>
        <InputText v-model="draftCityCodes" class="w-full" />
      </div>
      <div v-else-if="draftType === 'INTEREST_TAG'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.interest_tags') }}</label>
        <InputText v-model="draftInterestTags" class="w-full" />
      </div>
      <div v-else-if="draftType === 'ORG_TYPE'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.org_types') }}</label>
        <InputText v-model="draftOrgTypes" class="w-full" placeholder="MANSION, COMPANY" />
      </div>
      <div v-else-if="draftType === 'LOCALE'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.locales') }}</label>
        <MultiSelect v-model="draftLocales" :options="localeOptions" class="w-full" />
      </div>
      <div v-else-if="draftType === 'DEVICE'">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.segment_form.devices') }}</label>
        <MultiSelect v-model="draftDevices" :options="deviceOptions" class="w-full" />
      </div>

      <div class="mt-3 flex justify-end">
        <Button
          icon="pi pi-plus"
          :label="t('advertising.actions.add_segment')"
          :disabled="!draftType"
          size="small"
          @click="addSegment"
        />
      </div>
    </div>
  </div>
</template>
