<script setup lang="ts">
import { useDebounceFn } from '@vueuse/core'
import type { PrefectureResponse, CityResponse } from '~/types/matching'
import { generateSlug, isValidSlug } from '~/utils/slug'

const props = defineProps<{
  entityType: 'team' | 'organization'
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  created: [entity: { id: string; name: string }]
}>()

const api = useApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const { getPrefectures, getCities } = useMatchingApi()
const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})

const prefectures = ref<PrefectureResponse[]>([])
const cities = ref<CityResponse[]>([])
const selectedPref = ref<PrefectureResponse | null>(null)
// F22.1 Phase2 足場C 第三陣: 市区町村は名称だけでなくコードも保持する。
const selectedCity = ref<CityResponse | null>(null)
const citiesLoading = ref(false)

onMounted(async () => {
  try {
    const res = await getPrefectures()
    prefectures.value = res.data
  } catch {
    /* マスターデータ取得失敗は無視 */
  }
})

watch(selectedPref, async (pref) => {
  form.value.prefecture = pref?.name ?? ''
  form.value.prefectureCode = pref?.code ?? ''
  // 都道府県変更時は市区町村選択をリセット
  form.value.city = ''
  form.value.cityCode = ''
  selectedCity.value = null
  cities.value = []
  if (!pref) return
  citiesLoading.value = true
  try {
    const res = await getCities(pref.code)
    cities.value = res.data
  } catch {
    cities.value = []
  } finally {
    citiesLoading.value = false
  }
})

// 市区町村選択時は名称・コードの両方を form に反映する。
watch(selectedCity, (city) => {
  form.value.city = city?.name ?? ''
  form.value.cityCode = city?.code ?? ''
})

const isTeam = computed(() => props.entityType === 'team')
const title = computed(() => (isTeam.value ? 'チームを作成' : '組織を作成'))

const form = ref({
  name: '',
  nameKana: '',
  nickname1: '',
  description: '',
  prefecture: '',
  city: '',
  // F22.1 Phase2 足場C 第三陣: 構造化地域コード（BE prefectureCode/cityCode camelCase と 1:1）。
  prefectureCode: '',
  cityCode: '',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  // スラッグ（英小文字・数字・ハイフン、3〜30文字）
  slug: '',
  // Team only
  template: 'OTHER',
  // Org only
  orgType: 'OTHER',
})

// スラッグ可用性チェック
const slugChecking = ref(false)
const slugAvailable = ref<boolean | null>(null)

const checkSlug = useDebounceFn(async (slug: string) => {
  if (!slug || !isValidSlug(slug)) {
    slugAvailable.value = null
    return
  }
  slugChecking.value = true
  try {
    const endpoint = isTeam.value ? '/api/v1/teams/slug-check' : '/api/v1/organizations/slug-check'
    const res = await api<{ available: boolean }>(endpoint, {
      params: { slug },
    })
    slugAvailable.value = res.available
  } catch {
    slugAvailable.value = null
  } finally {
    slugChecking.value = false
  }
}, 300)

// 名前が変わったらスラッグを自動生成
watch(
  () => form.value.name,
  (name) => {
    if (name && !form.value.slug) {
      form.value.slug = generateSlug(name)
      checkSlug(form.value.slug)
    }
  },
)

function onSlugInput() {
  slugAvailable.value = null
  checkSlug(form.value.slug)
}

const visibilityOptions = computed(() => {
  if (isTeam.value) {
    return [
      { label: '公開', value: 'PUBLIC' },
      { label: 'チーム内のみ', value: 'ORGANIZATION_ONLY' },
      { label: '非公開', value: 'PRIVATE' },
    ]
  }
  return [
    { label: '公開', value: 'PUBLIC' },
    { label: '非公開', value: 'PRIVATE' },
  ]
})

const templateOptions = [
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

async function submit() {
  submitting.value = true
  fieldErrors.value = {}
  try {
    const endpoint = isTeam.value ? '/api/v1/teams' : '/api/v1/organizations'
    const body: Record<string, unknown> = {
      name: form.value.name,
      nameKana: form.value.nameKana || undefined,
      nickname1: form.value.nickname1 || undefined,
      description: form.value.description || undefined,
      prefecture: form.value.prefecture || undefined,
      city: form.value.city || undefined,
      visibility: form.value.visibility,
      supporterEnabled: form.value.supporterEnabled,
    }
    if (form.value.slug) {
      body.slug = form.value.slug
    }
    if (isTeam.value) {
      body.template = form.value.template
      // F22.1 Phase2 足場C 第三陣: チーム作成時のみ構造化地域コードを送る
      // （BE CreateTeamRequest.prefectureCode/cityCode。組織作成 API は未対応のため送らない）。
      body.prefectureCode = form.value.prefectureCode || undefined
      body.cityCode = form.value.cityCode || undefined
    } else {
      body.orgType = form.value.orgType
    }

    const response = await api<{ data: { id: string; name: string } }>(endpoint, {
      method: 'POST',
      body,
    })
    notification.success(`${isTeam.value ? 'チーム' : '組織'}を作成しました`)
    emit('created', response.data)
    emit('update:visible', false)
    resetForm()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0) {
      handleApiError(error, isTeam.value ? 'チーム作成' : '組織作成')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    name: '',
    nameKana: '',
    nickname1: '',
    description: '',
    prefecture: '',
    city: '',
    prefectureCode: '',
    cityCode: '',
    visibility: 'PUBLIC',
    supporterEnabled: false,
    slug: '',
    template: 'OTHER',
    orgType: 'OTHER',
  }
  selectedPref.value = null
  selectedCity.value = null
  cities.value = []
  fieldErrors.value = {}
  slugAvailable.value = null
}

function close() {
  emit('update:visible', false)
  resetForm()
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="title"
    :style="{ width: '500px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 名前 -->
      <div>
        <label class="mb-1 block text-sm font-medium"
          >名前 <span class="text-red-500">*</span></label
        >
        <InputText v-model="form.name" class="w-full" :class="{ 'p-invalid': fieldErrors.name }" />
        <small v-if="fieldErrors.name" class="text-red-500">{{ fieldErrors.name }}</small>
      </div>

      <!-- URLスラッグ -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ $t('slug.label') }}
        </label>
        <div class="relative">
          <InputText
            v-model="form.slug"
            class="w-full"
            :placeholder="$t('slug.hint')"
            :maxlength="30"
            @input="onSlugInput"
          />
        </div>
        <small class="mt-1 block text-xs text-gray-500">{{ $t('slug.format') }}</small>
        <small v-if="slugChecking" class="text-gray-400 text-xs">{{ $t('slug.checking') }}</small>
        <small v-else-if="slugAvailable === true" class="text-green-600 text-xs">✓ {{ $t('slug.available') }}</small>
        <small v-else-if="slugAvailable === false" class="text-red-500 text-xs">✗ {{ $t('slug.unavailable') }}</small>
        <small v-if="fieldErrors.slug" class="text-red-500 text-xs">{{ fieldErrors.slug }}</small>
      </div>

      <!-- 名前（カナ） -->
      <div>
        <label class="mb-1 block text-sm font-medium">名前（カナ）</label>
        <InputText v-model="form.nameKana" class="w-full" />
      </div>

      <!-- ニックネーム -->
      <div>
        <label class="mb-1 block text-sm font-medium">ニックネーム</label>
        <InputText v-model="form.nickname1" class="w-full" />
      </div>

      <!-- ジャンル（チームのみ） -->
      <div v-if="isTeam">
        <label class="mb-1 block text-sm font-medium">ジャンル</label>
        <Select
          v-model="form.template"
          :options="templateOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- ジャンル（組織のみ） -->
      <div v-if="!isTeam">
        <label class="mb-1 block text-sm font-medium">ジャンル</label>
        <Select
          v-model="form.orgType"
          :options="orgTypeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 公開設定 -->
      <div>
        <label class="mb-1 block text-sm font-medium">公開設定</label>
        <Select
          v-model="form.visibility"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 都道府県 + 市区町村 -->
      <div class="grid grid-cols-2 gap-3">
        <div>
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
        <div>
          <label class="mb-1 block text-sm font-medium">市区町村</label>
          <Select
            v-model="selectedCity"
            :options="cities"
            option-label="name"
            placeholder="都道府県を先に選択"
            filter
            filter-placeholder="市区町村を検索"
            show-clear
            :disabled="!selectedPref || citiesLoading"
            :loading="citiesLoading"
            class="w-full"
          />
        </div>
      </div>

      <!-- 説明 -->
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" rows="3" class="w-full" />
      </div>

      <!-- サポーター有効 -->
      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="form.supporterEnabled" />
        <label class="text-sm">サポーター機能を有効にする</label>
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button :label="title" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
