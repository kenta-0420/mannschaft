<script setup lang="ts">
import type { CityResponse, PrefectureResponse } from '~/types/matching'

interface Props {
  teamId: string
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  templateLabel: string
  prefecture: string | null
  city: string | null
  // F22.1 Phase2 足場C 第三陣: 構造化地域コード（編集対象）
  prefectureCode: string | null
  cityCode: string | null
  visibility: string
  visibilityLabel: string
  memberCount: number
  teamFriendCount: number
  supporterCount: number
  supporterEnabled: boolean
  description: string | null
  isAdmin: boolean
  // F15.4 Phase 5-β: Google Maps 埋め込み URL
  mapEmbedUrl: string | null
  /** 予約枠の基準タイムゾーン（IANA）。 */
  timezone: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'updated:mapEmbedUrl', value: string | null): void
  // F22.1 Phase2 足場C 第三陣: 地域コード保存後に親へ反映通知
  (e: 'updated:regionCodes', prefectureCode: string | null, cityCode: string | null): void
  (e: 'updated:timezone', value: string): void
}>()

const { t } = useI18n()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { getPrefectures, getCities } = useMatchingApi()

// =====================================================================
// F22.1 Phase2 足場C 第三陣: 所在地（地域コード）編集
// =====================================================================
const prefectures = ref<PrefectureResponse[]>([])
const regionCities = ref<CityResponse[]>([])
const selectedPrefCode = ref<string | null>(props.prefectureCode)
const selectedCityCode = ref<string | null>(props.cityCode)
const regionCitiesLoading = ref(false)
const regionSaving = ref(false)

watch(() => props.prefectureCode, (v) => { selectedPrefCode.value = v })
watch(() => props.cityCode, (v) => { selectedCityCode.value = v })

async function loadRegionCities(prefCode: string | null) {
  regionCities.value = []
  if (!prefCode) return
  regionCitiesLoading.value = true
  try {
    const res = await getCities(prefCode)
    regionCities.value = res.data
  } catch {
    regionCities.value = []
  } finally {
    regionCitiesLoading.value = false
  }
}

async function onRegionPrefChange(code: string | null) {
  selectedPrefCode.value = code
  // 都道府県変更時は市区町村をリセット
  selectedCityCode.value = null
  await loadRegionCities(code)
}

async function saveRegionCodes() {
  regionSaving.value = true
  try {
    // 都道府県が空なら市区町村も空に正規化して送る。
    const prefCode = selectedPrefCode.value || undefined
    const cityCode = prefCode ? (selectedCityCode.value || undefined) : undefined
    await teamApi.updateTeam(props.teamId, {
      prefectureCode: prefCode,
      cityCode,
    })
    emit('updated:regionCodes', prefCode ?? null, cityCode ?? null)
    notification.success(t('team.regionCode.saved'))
  } catch (error) {
    handleApiError(error, t('team.regionCode.label'))
  } finally {
    regionSaving.value = false
  }
}

onMounted(async () => {
  if (!props.isAdmin) return
  try {
    const res = await getPrefectures()
    prefectures.value = res.data
  } catch {
    /* マスターデータ取得失敗は無視（編集セレクタが空になるだけ） */
  }
  if (selectedPrefCode.value) await loadRegionCities(selectedPrefCode.value)
})

// F15.4 Phase 5-β: 地図 URL 編集状態
const mapEmbedUrlInput = ref<string>(props.mapEmbedUrl ?? '')
const mapEmbedUrlSaving = ref(false)

// 親から渡される props.mapEmbedUrl の変化に追従
watch(
  () => props.mapEmbedUrl,
  (val) => {
    mapEmbedUrlInput.value = val ?? ''
  },
)

// バリデーション: 空文字は OK（null として送信）、それ以外は Google Maps embed 形式必須
const mapEmbedUrlPattern = /^https:\/\/www\.google\.com\/maps\/embed\?.*$/
const mapEmbedUrlError = computed<string | null>(() => {
  const v = mapEmbedUrlInput.value.trim()
  if (!v) return null
  if (!mapEmbedUrlPattern.test(v)) {
    return t('team.mapEmbedUrlInvalidFormat')
  }
  return null
})

async function saveMapEmbedUrl() {
  if (mapEmbedUrlError.value) return
  mapEmbedUrlSaving.value = true
  try {
    const trimmed = mapEmbedUrlInput.value.trim()
    const payload = { mapEmbedUrl: trimmed === '' ? null : trimmed }
    const res = await teamApi.updateTeam(props.teamId, payload)
    const newUrl = res.data?.metadata?.mapEmbedUrl ?? null
    emit('updated:mapEmbedUrl', newUrl)
    notification.success(t('team.mapEmbedUrlSaved'))
  } catch (error) {
    handleApiError(error, t('team.mapEmbedUrlLabel'))
  } finally {
    mapEmbedUrlSaving.value = false
  }
}

// 予約枠の基準タイムゾーン編集
const timezoneInput = ref(props.timezone || 'Asia/Tokyo')
const timezoneSaving = ref(false)
const timezoneError = ref<string | null>(null)
watch(() => props.timezone, (value) => {
  timezoneInput.value = value || 'Asia/Tokyo'
})

function validateTimezone(value: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format()
    return true
  } catch {
    return false
  }
}

async function saveTimezone() {
  const value = timezoneInput.value.trim() || 'Asia/Tokyo'
  if (!validateTimezone(value)) {
    timezoneError.value = t('team.timezone.invalid')
    return
  }
  timezoneError.value = null
  timezoneSaving.value = true
  try {
    const res = await teamApi.updateTeam(props.teamId, { timezone: value })
    const saved = res.data?.timezone ?? value
    timezoneInput.value = saved
    emit('updated:timezone', saved)
    notification.success(t('team.timezone.saved'))
  } catch (error) {
    handleApiError(error, t('team.timezone.label'))
  } finally {
    timezoneSaving.value = false
  }
}
</script>

<template>
  <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
    <div class="space-y-4">
      <div v-if="isAdmin" class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
        <label for="team-timezone" class="text-sm font-medium text-gray-500">
          {{ $t('team.timezone.label') }}
        </label>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">
          {{ $t('team.timezone.help') }}
        </p>
        <InputText
          id="team-timezone"
          v-model="timezoneInput"
          list="team-timezone-options"
          class="mt-2 w-full"
          :placeholder="$t('team.timezone.placeholder')"
          :invalid="!!timezoneError"
          data-testid="team-timezone-input"
        />
        <datalist id="team-timezone-options">
          <option value="Asia/Tokyo" />
          <option value="Asia/Seoul" />
          <option value="Asia/Shanghai" />
          <option value="America/Los_Angeles" />
          <option value="America/New_York" />
          <option value="Europe/London" />
          <option value="UTC" />
        </datalist>
        <p v-if="timezoneError" class="mt-1 text-sm text-red-600 dark:text-red-400">
          {{ timezoneError }}
        </p>
        <Button
          class="mt-2"
          size="small"
          :label="$t('common.save')"
          :loading="timezoneSaving"
          :disabled="timezoneSaving"
          data-testid="team-timezone-save"
          @click="saveTimezone"
        />
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">チーム名</label>
        <p class="mt-1">
          {{ name }}
        </p>
      </div>
      <div v-if="nameKana">
        <label class="text-sm font-medium text-gray-500">チーム名（カナ）</label>
        <p class="mt-1">
          {{ nameKana }}
        </p>
      </div>
      <div v-if="nickname1">
        <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
        <p class="mt-1">
          {{ nickname1 }}
        </p>
      </div>
      <div v-if="nickname2">
        <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
        <p class="mt-1">
          {{ nickname2 }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">ジャンル</label>
        <p class="mt-1">
          {{ templateLabel }}
        </p>
      </div>
    </div>
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">所在地</label>
        <p class="mt-1">
          {{ [prefecture, city].filter(Boolean).join(' ') || '未設定' }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">公開設定</label>
        <p class="mt-1">
          {{ visibilityLabel }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">メンバー数</label>
        <p class="mt-1">{{ memberCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">{{ $t('label.teamFriendCount') }}</label>
        <p class="mt-1">{{ teamFriendCount }}チーム</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">{{ $t('label.supporterCount') }}</label>
        <p class="mt-1">{{ supporterCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">サポーター機能</label>
        <p class="mt-1">
          {{ supporterEnabled ? '有効' : '無効' }}
        </p>
      </div>
      <div v-if="description">
        <label class="text-sm font-medium text-gray-500">説明</label>
        <p class="mt-1 whitespace-pre-wrap">
          {{ description }}
        </p>
      </div>
    </div>
  </div>

  <!-- F15.4 Phase 5-β: 店舗地図埋め込み URL（管理者のみ編集可能） -->
  <section
    v-if="isAdmin"
    class="mt-6 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900"
    data-testid="team-map-embed-url-section"
  >
    <h3 class="mb-2 text-base font-semibold text-surface-700 dark:text-surface-200">
      {{ $t('team.mapEmbedUrlLabel') }}
    </h3>
    <p
      class="mb-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200"
    >
      {{ $t('team.mapEmbedUrlWarning') }}
    </p>
    <InputText
      v-model="mapEmbedUrlInput"
      class="w-full"
      :placeholder="$t('team.mapEmbedUrlPlaceholder')"
      :invalid="!!mapEmbedUrlError"
      data-testid="team-map-embed-url-input"
    />
    <p v-if="mapEmbedUrlError" class="mt-1 text-sm text-red-600 dark:text-red-400">
      {{ mapEmbedUrlError }}
    </p>
    <p class="mt-2 text-xs text-surface-500 dark:text-surface-400">
      {{ $t('team.mapEmbedUrlHelp') }}
    </p>
    <div class="mt-3 flex justify-end">
      <Button
        :label="$t('button.save')"
        icon="pi pi-check"
        :loading="mapEmbedUrlSaving"
        :disabled="!!mapEmbedUrlError"
        data-testid="team-map-embed-url-save"
        @click="saveMapEmbedUrl"
      />
    </div>
  </section>

  <!-- F22.1 Phase2 足場C 第三陣: 所在地（地域コード）編集（管理者のみ） -->
  <section
    v-if="isAdmin"
    class="mt-6 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900"
    data-testid="team-region-code-section"
  >
    <h3 class="mb-2 text-base font-semibold text-surface-700 dark:text-surface-200">
      {{ $t('team.regionCode.label') }}
    </h3>
    <p class="mb-3 text-sm text-surface-500 dark:text-surface-400">
      {{ $t('team.regionCode.help') }}
    </p>
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium text-surface-600 dark:text-surface-300">
          {{ $t('team.regionCode.prefecture') }}
        </label>
        <Select
          :model-value="selectedPrefCode"
          :options="prefectures"
          option-label="name"
          option-value="code"
          :placeholder="$t('team.regionCode.prefecturePlaceholder')"
          filter
          show-clear
          class="w-full"
          data-testid="team-region-code-prefecture"
          @update:model-value="onRegionPrefChange"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium text-surface-600 dark:text-surface-300">
          {{ $t('team.regionCode.city') }}
        </label>
        <Select
          v-model="selectedCityCode"
          :options="regionCities"
          option-label="name"
          option-value="code"
          :placeholder="$t('team.regionCode.cityPlaceholder')"
          filter
          show-clear
          :disabled="!selectedPrefCode || regionCitiesLoading"
          :loading="regionCitiesLoading"
          class="w-full"
          data-testid="team-region-code-city"
        />
      </div>
    </div>
    <div class="mt-3 flex justify-end">
      <Button
        :label="$t('button.save')"
        icon="pi pi-check"
        :loading="regionSaving"
        data-testid="team-region-code-save"
        @click="saveRegionCodes"
      />
    </div>
  </section>

  <TeamExtendedProfileDisplay
    :team-id="teamId"
    :is-admin-or-deputy="isAdmin"
  />
</template>
