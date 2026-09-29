<script setup lang="ts">
import type { PrefectureResponse } from '~/types/matching'

const props = defineProps<{
  placeholder?: string
  showTemplateFilter?: boolean
  showOrgTypeFilter?: boolean
  /** F22.1: URL クエリ等から渡す初期キーワード（検索ページ遷移時の初期値復元用）。 */
  initialKeyword?: string
}>()

const emit = defineEmits<{
  /**
   * F22.1 Phase2 足場C 第三陣: 地域はコード送信を優先する。
   * `prefectureCode`（JIS X 0401・2 桁）を併せて emit し、親はコード優先で BE に送る。
   * `prefecture`（名称）は表示・後方互換用に残す。
   */
  search: [
    params: {
      keyword: string
      prefecture: string
      prefectureCode: string
      template: string
      orgType: string
    },
  ]
}>()

const { getPrefectures } = useMatchingApi()
const { t } = useI18n()

const keyword = ref(props.initialKeyword ?? '')

// 親が initialKeyword を後から確定する場合（onMounted での route.query 読み取り）にも追従する。
watch(
  () => props.initialKeyword,
  (kw) => {
    if (kw !== undefined) keyword.value = kw
  },
)
const selectedPref = ref<PrefectureResponse | null>(null)
const template = ref('')
const orgType = ref('')

const prefectures = ref<PrefectureResponse[]>([])

onMounted(async () => {
  try {
    const res = await getPrefectures()
    prefectures.value = res.data
  } catch {
    /* マスターデータ取得失敗は無視 */
  }
})

const prefecture = computed(() => selectedPref.value?.name ?? '')
const prefectureCode = computed(() => selectedPref.value?.code ?? '')

const templateValues = [
  'CLUB',
  'CLINIC',
  'CLASS',
  'COMMUNITY',
  'COMPANY',
  'FAMILY',
  'RESTAURANT',
  'BEAUTY',
  'STORE',
  'VOLUNTEER',
  'NEIGHBORHOOD',
  'CONDO',
  'OTHER',
] as const

const organizationTypeValues = [
  'GOVERNMENT',
  'MUNICIPALITY',
  'COMPANY',
  'HOSPITAL',
  'ASSOCIATION',
  'SCHOOL',
  'NPO',
  'COMMUNITY',
  'OTHER',
] as const

const templateOptions = computed(() => [
  { label: t('searchBar.all'), value: '' },
  ...templateValues.map((value) => ({
    label: t(`scopeLabels.template.${value}`),
    value,
  })),
])

const orgTypeOptions = computed(() => [
  { label: t('searchBar.all'), value: '' },
  ...organizationTypeValues.map((value) => ({
    label: t(`scopeLabels.organizationType.${value}`),
    value,
  })),
])

function onSearch() {
  emit('search', {
    keyword: keyword.value,
    prefecture: prefecture.value,
    prefectureCode: prefectureCode.value,
    template: template.value,
    orgType: orgType.value,
  })
}
</script>

<template>
  <div class="flex flex-wrap items-end gap-3">
    <div class="min-w-48 flex-1">
      <label class="mb-1 block text-sm font-medium">{{ t('searchBar.keyword') }}</label>
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="keyword"
          :placeholder="placeholder ?? t('searchBar.defaultPlaceholder')"
          class="w-full"
          @keyup.enter="onSearch"
        />
      </IconField>
    </div>
    <div class="w-44">
      <label class="mb-1 block text-sm font-medium">{{ t('searchBar.prefecture') }}</label>
      <Select
        v-model="selectedPref"
        :options="prefectures"
        option-label="name"
        :placeholder="t('searchBar.selectPrefecture')"
        filter
        :filter-placeholder="t('searchBar.filterPrefecture')"
        show-clear
        class="w-full"
      />
    </div>
    <div v-if="showTemplateFilter" class="w-40">
      <label class="mb-1 block text-sm font-medium">{{ t('searchBar.genre') }}</label>
      <Select
        v-model="template"
        :options="templateOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <div v-if="showOrgTypeFilter" class="w-44">
      <label class="mb-1 block text-sm font-medium">{{ t('searchBar.genre') }}</label>
      <Select
        v-model="orgType"
        :options="orgTypeOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <Button :label="t('button.search')" icon="pi pi-search" @click="onSearch" />
  </div>
</template>
