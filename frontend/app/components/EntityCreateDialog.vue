<script setup lang="ts">
import type { PrefectureResponse, CityResponse } from '~/types/matching'
import type { SlugAvailabilityResponse, SlugUnavailableReason } from '~/types/slug'
import { generateSlug, isSlugFormatValid } from '~/utils/slug'

const props = defineProps<{
  entityType: 'team' | 'organization'
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  created: [entity: { id: string; name: string; slug: string }]
}>()

const api = useApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const { getPrefectures, getCities } = useMatchingApi()
const { checkTeamSlugAvailable } = useTeamApi()
const { checkOrganizationSlugAvailable } = useOrganizationApi()
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
  // Team only
  template: 'OTHER',
  // Org only
  orgType: 'OTHER',
})

const { t } = useI18n()

// === slug（村方式: ユーザーが任意入力。空欄なら BE が名前から自動生成） ===
const slug = ref('')
// ユーザーが slug 欄を手動編集したら、名前変更による自動提案の上書きを止める。
const slugManuallyEdited = ref(false)
type SlugStatus = 'idle' | 'checking' | 'available' | 'unavailable' | 'invalid'
const slugStatus = ref<SlugStatus>('idle')
// available=false のとき BE が返す理由コード。表示メッセージのマッピングに使う。
const slugReason = ref<SlugUnavailableReason | null>(null)
let slugCheckTimer: ReturnType<typeof setTimeout> | null = null
// 連打レースで古い応答が新しい結果を上書きしないようにする世代カウンタ。
let slugCheckSeq = 0

/** entityType に応じた可用性チェック EP を呼ぶ。 */
function checkSlugAvailable(target: string): Promise<SlugAvailabilityResponse> {
  return isTeam.value
    ? checkTeamSlugAvailable(target)
    : checkOrganizationSlugAvailable(target)
}

/** 名前入力に追従して slug を自動提案する（ユーザーが手動編集していない間のみ）。 */
watch(
  () => form.value.name,
  (name) => {
    if (slugManuallyEdited.value) return
    slug.value = generateSlug(name)
    scheduleSlugCheck()
  },
)

/** slug 入力欄の手動編集ハンドラ。以後は名前追従を停止し、可用性チェックを予約する。 */
function onSlugInput() {
  slugManuallyEdited.value = true
  slug.value = slug.value.trim()
  scheduleSlugCheck()
}

/** デバウンス（400ms）して可用性チェックを予約する。形式不正は即時表示し EP は叩かない。 */
function scheduleSlugCheck() {
  slugReason.value = null
  if (slugCheckTimer) {
    clearTimeout(slugCheckTimer)
    slugCheckTimer = null
  }
  const current = slug.value.trim()
  if (!current) {
    // 空欄は「BE が自動生成」なので未確認（idle）扱い。送信前ガードでも通す。
    slugStatus.value = 'idle'
    return
  }
  if (!isSlugFormatValid(current)) {
    slugStatus.value = 'invalid'
    return
  }
  slugStatus.value = 'checking'
  slugCheckTimer = setTimeout(() => {
    void runSlugCheck(current)
  }, 400)
}

/** 実際に可用性チェック EP を叩き、最新世代の応答だけを反映する。 */
async function runSlugCheck(target: string) {
  const seq = ++slugCheckSeq
  try {
    const res: SlugAvailabilityResponse = await checkSlugAvailable(target)
    if (seq !== slugCheckSeq) return // 古い応答は破棄
    if (res.available) {
      slugStatus.value = 'available'
      slugReason.value = null
    } else {
      slugStatus.value = 'unavailable'
      slugReason.value = res.reason ?? 'SLUG_ALREADY_TAKEN'
    }
  } catch {
    if (seq !== slugCheckSeq) return
    // 可用性チェック失敗は確定不能。送信は許容し、最終判定は BE 側エラーに委ねる。
    slugStatus.value = 'idle'
    slugReason.value = null
  }
}

/** 入力中の slug が「送信不可」状態か（形式不正・重複・予約語）。空欄/確認中/未確認は許容。 */
const slugBlocksSubmit = computed(
  () => slugStatus.value === 'invalid' || slugStatus.value === 'unavailable',
)

/** 可用性メッセージ。idle/checking では補助文言、それ以外は状態に応じた文言。 */
const slugMessage = computed<string | null>(() => {
  switch (slugStatus.value) {
    case 'checking':
      return t('slug.checking')
    case 'available':
      return t('slug.available')
    case 'invalid':
      return t('slug.format')
    case 'unavailable':
      return slugReason.value ? t(`slug.reason.${slugReason.value}`) : t('slug.unavailable')
    default:
      return null
  }
})

const visibilityOptions = computed(() => {
  if (isTeam.value) {
    return [
      { label: t('team_visibility.PUBLIC'), value: 'PUBLIC' },
      { label: t('team_visibility.GUESTS_AND_ABOVE'), value: 'GUESTS_AND_ABOVE' },
      { label: t('team_visibility.SUPPORTERS_AND_ABOVE'), value: 'SUPPORTERS_AND_ABOVE' },
      { label: t('team_visibility.MEMBERS_AND_ABOVE'), value: 'MEMBERS_AND_ABOVE' },
    ]
  }
  return [
    { label: t('team_visibility.PUBLIC'), value: 'PUBLIC' },
    { label: t('team_visibility.MEMBERS_AND_ABOVE'), value: 'MEMBERS_AND_ABOVE' },
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
  // 送信前ガード: 形式不正・重複・予約語の slug は送らない（空欄は BE 自動生成に委ねる）。
  if (slugBlocksSubmit.value) {
    fieldErrors.value = { slug: slugMessage.value ?? t('slug.format') }
    return
  }
  submitting.value = true
  fieldErrors.value = {}
  try {
    const endpoint = isTeam.value ? '/api/v1/teams' : '/api/v1/organizations'
    const trimmedSlug = slug.value.trim()
    const body: Record<string, unknown> = {
      name: form.value.name,
      // 村方式: 空欄なら slug を送らず BE の自動生成に委ねる。
      slug: trimmedSlug || undefined,
      nameKana: form.value.nameKana || undefined,
      nickname1: form.value.nickname1 || undefined,
      description: form.value.description || undefined,
      prefecture: form.value.prefecture || undefined,
      city: form.value.city || undefined,
      visibility: form.value.visibility,
      supporterEnabled: form.value.supporterEnabled,
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

    const response = await api<{ data: { id: string; name: string; slug: string } }>(endpoint, {
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
    template: 'OTHER',
    orgType: 'OTHER',
  }
  selectedPref.value = null
  selectedCity.value = null
  cities.value = []
  fieldErrors.value = {}
  // slug 状態もリセット
  slug.value = ''
  slugManuallyEdited.value = false
  slugStatus.value = 'idle'
  slugReason.value = null
  if (slugCheckTimer) {
    clearTimeout(slugCheckTimer)
    slugCheckTimer = null
  }
  slugCheckSeq++
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

      <!-- URL（slug） -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('slug.label') }}</label>
        <InputText
          v-model="slug"
          class="w-full"
          :placeholder="t('slug.placeholder')"
          :class="{ 'p-invalid': slugBlocksSubmit || !!fieldErrors.slug }"
          data-testid="entity-create-slug"
          @input="onSlugInput"
        />
        <small class="mt-1 block text-gray-500">{{ t('slug.hint') }}</small>
        <small
          v-if="slugMessage"
          class="mt-1 block"
          :class="{
            'text-green-600': slugStatus === 'available',
            'text-red-500': slugStatus === 'invalid' || slugStatus === 'unavailable',
            'text-gray-500': slugStatus === 'checking',
          }"
          data-testid="entity-create-slug-message"
        >
          <i
            v-if="slugStatus === 'available'"
            class="pi pi-check mr-1"
          />
          <i
            v-else-if="slugStatus === 'invalid' || slugStatus === 'unavailable'"
            class="pi pi-times mr-1"
          />
          {{ slugMessage }}
        </small>
        <small v-if="fieldErrors.slug" class="mt-1 block text-red-500">{{ fieldErrors.slug }}</small>
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
      <Button
        :label="title"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="slugBlocksSubmit"
        data-testid="entity-create-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
