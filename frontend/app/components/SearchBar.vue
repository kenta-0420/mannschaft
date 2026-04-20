<script setup lang="ts">
import type { PrefectureResponse } from '~/types/matching'

defineProps<{
  placeholder?: string
  showTemplateFilter?: boolean
  showOrgTypeFilter?: boolean
}>()

const emit = defineEmits<{
  search: [params: { keyword: string; prefecture: string; template: string; orgType: string }]
}>()

const { getPrefectures } = useMatchingApi()

const keyword = ref('')
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

const templateOptions = [
  { label: '全て', value: '' },
  { label: 'クラブ・サークル', value: 'CLUB' },
  { label: 'クリニック', value: 'CLINIC' },
  { label: 'クラス', value: 'CLASS' },
  { label: 'コミュニティ', value: 'COMMUNITY' },
  { label: '企業', value: 'COMPANY' },
  { label: '家族', value: 'FAMILY' },
  { label: '飲食店', value: 'RESTAURANT' },
  { label: '美容院・サロン', value: 'BEAUTY' },
  { label: '店舗・小売', value: 'STORE' },
  { label: 'ボランティア・NPO', value: 'VOLUNTEER' },
  { label: '自治会', value: 'NEIGHBORHOOD' },
  { label: 'マンション管理組合', value: 'CONDO' },
  { label: 'その他', value: 'OTHER' },
]

const orgTypeOptions = [
  { label: '全て', value: '' },
  { label: '行政・官公庁', value: 'GOVERNMENT' },
  { label: '自治体（市区町村）', value: 'MUNICIPALITY' },
  { label: '会社・企業', value: 'COMPANY' },
  { label: '病院・医療機関', value: 'HOSPITAL' },
  { label: '協会・連盟', value: 'ASSOCIATION' },
  { label: '学校・教育機関', value: 'SCHOOL' },
  { label: 'NPO・非営利団体', value: 'NPO' },
  { label: 'コミュニティ', value: 'COMMUNITY' },
  { label: 'その他', value: 'OTHER' },
]

function onSearch() {
  emit('search', {
    keyword: keyword.value,
    prefecture: prefecture.value,
    template: template.value,
    orgType: orgType.value,
  })
}
</script>

<template>
  <div class="flex flex-wrap items-end gap-3">
    <div class="min-w-48 flex-1">
      <label class="mb-1 block text-sm font-medium">キーワード</label>
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="keyword"
          :placeholder="placeholder ?? '名前で検索'"
          class="w-full"
          @keyup.enter="onSearch"
        />
      </IconField>
    </div>
    <div class="w-44">
      <label class="mb-1 block text-sm font-medium">都道府県</label>
      <Select
        v-model="selectedPref"
        :options="prefectures"
        option-label="name"
        placeholder="選択してください"
        filter
        filter-placeholder="都道府県を検索"
        show-clear
        class="w-full"
      />
    </div>
    <div v-if="showTemplateFilter" class="w-40">
      <label class="mb-1 block text-sm font-medium">ジャンル</label>
      <Select
        v-model="template"
        :options="templateOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <div v-if="showOrgTypeFilter" class="w-44">
      <label class="mb-1 block text-sm font-medium">ジャンル</label>
      <Select
        v-model="orgType"
        :options="orgTypeOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <Button label="検索" icon="pi pi-search" @click="onSearch" />
  </div>
</template>
