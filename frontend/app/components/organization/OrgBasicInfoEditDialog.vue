<script setup lang="ts">
/**
 * CMP-260907-0852 組織「基本情報」編集ダイアログ。
 *
 * <p>これまで組織名・所在地を編集できる導線はアプリ全体で「お気に入りクイック編集ダイアログ」
 * だけだった。基本情報タブに表示されている項目（組織名・カナ・ニックネーム・所在地）を、
 * 表示している場所で直せるようにする。</p>
 *
 * <p>保存先は既存の `PATCH /api/v1/organizations/{slug}`（`useOrganizationApi.updateOrganization`）。
 * BE `UpdateOrganizationRequest` が受けるフィールドのみを送る
 * （name / nameKana / nickname1 / nickname2 / prefecture / city / version）。
 * `version` は `@NotNull` かつ楽観ロックに使われるため必須である。</p>
 *
 * <p>BE `OrganizationEntity.applyUpdate` は「null は無変更・値は上書き」の意味論なので、
 * クリアしたい任意項目は空文字で送る（null は送らない）。</p>
 */
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { PrefectureResponse, CityResponse } from '~/types/matching'

const props = defineProps<{
  /** URL 識別子（slug）。PATCH のパスに使う。 */
  orgId: string
  /** 現在の組織詳細（プリフィル元）。 */
  org: OrgDetail
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{ saved: [] }>()

const { t } = useI18n()
const { updateOrganization } = useOrganizationApi()
const { getPrefectures, getCities } = useMatchingApi()
const { success: showSuccess } = useNotification()
const { handleApiError } = useErrorHandler()

interface BasicInfoForm {
  name: string
  nameKana: string
  nickname1: string
  nickname2: string
  prefecture: string
  city: string
}

const form = ref<BasicInfoForm>(emptyForm())
const saving = ref(false)
const nameError = ref<string | null>(null)

// 都道府県／市区町村マスタ（EntityCreateDialog と同じ useMatchingApi 経由）
const prefectures = ref<PrefectureResponse[]>([])
const cities = ref<CityResponse[]>([])
const selectedPref = ref<PrefectureResponse | null>(null)
const citiesLoading = ref(false)

function emptyForm(): BasicInfoForm {
  return { name: '', nameKana: '', nickname1: '', nickname2: '', prefecture: '', city: '' }
}

function fillFromProps() {
  form.value = {
    name: props.org.basicInfo?.name ?? '',
    nameKana: props.org.basicInfo?.nameKana ?? '',
    nickname1: props.org.basicInfo?.nickname1 ?? '',
    nickname2: props.org.basicInfo?.nickname2 ?? '',
    prefecture: props.org.location?.prefecture ?? '',
    city: props.org.location?.city ?? '',
  }
  nameError.value = null
  selectedPref.value
    = prefectures.value.find(p => p.name === form.value.prefecture) ?? null
}

async function loadPrefectures() {
  if (prefectures.value.length > 0) return
  try {
    const res = await getPrefectures()
    prefectures.value = res.data
  } catch (err) {
    // マスタが引けなくても名称は既存値のまま保存できるため、致命ではない。
    // 握りつぶさず、原因はコンソールへ残す。
    console.error('[org-basic-info] 都道府県マスタの取得に失敗しました', err)
  }
}

async function loadCities(code: string) {
  citiesLoading.value = true
  try {
    const res = await getCities(code)
    cities.value = res.data
  } catch (err) {
    cities.value = []
    console.error('[org-basic-info] 市区町村マスタの取得に失敗しました', err)
  } finally {
    citiesLoading.value = false
  }
}

// 開いたときにマスタを取り直し、現在値でプリフィルする。
// immediate: true — 生成時点で既に visible=true の場合（親が v-if ではなく常時描画する場合）にも
// プリフィルが走るようにする。閉じている間は先頭で return するため無駄な取得はしない。
watch(visible, async (open) => {
  if (!open) return
  await loadPrefectures()
  fillFromProps()
  if (selectedPref.value) await loadCities(selectedPref.value.code)
}, { immediate: true })

// 都道府県を選び直したら市区町村候補を入れ替える。
// 市区町村のクリアは「都道府県が実際に変わったとき」だけに限る。
// watch の第2引数 prev で判定すると、プリフィル時（null → 現在の都道府県）も
// 「変わった」と見なされて既存の市区町村が消えてしまうため、フォームの現在値と比較する。
watch(selectedPref, async (pref) => {
  if (!visible.value) return
  const nextPrefecture = pref?.name ?? ''
  const changed = nextPrefecture !== form.value.prefecture
  form.value.prefecture = nextPrefecture
  if (changed) {
    form.value.city = ''
    cities.value = []
  }
  if (pref) await loadCities(pref.code)
})

const canSubmit = computed(() => form.value.name.trim().length > 0 && !saving.value)

async function handleSave() {
  if (form.value.name.trim().length === 0) {
    nameError.value = t('organization.basicInfoEdit.nameRequired')
    return
  }
  nameError.value = null

  const version = props.org.metadata?.version
  if (version === undefined) {
    // version は BE で @NotNull。取得できていないなら送らずに理由を見せる（黙って壊さない）。
    handleApiError(
      new Error(t('organization.basicInfoEdit.versionMissing')),
      'organization.basicInfoEdit',
    )
    return
  }

  saving.value = true
  try {
    // BE applyUpdate は「null=無変更 / 値=上書き」。空欄は空文字で送ってクリアを表現する。
    await updateOrganization(props.orgId, {
      name: form.value.name.trim(),
      nameKana: form.value.nameKana.trim(),
      nickname1: form.value.nickname1.trim(),
      nickname2: form.value.nickname2.trim(),
      prefecture: form.value.prefecture.trim(),
      city: form.value.city.trim(),
      version,
    })
    showSuccess(t('organization.basicInfoEdit.saved'))
    visible.value = false
    emit('saved')
  } catch (err) {
    handleApiError(err, 'organization.basicInfoEdit')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('organization.basicInfoEdit.title')"
    :style="{ width: '560px' }"
    :breakpoints="{ '960px': '90vw' }"
  >
    <div class="flex flex-col gap-4" data-testid="org-basic-info-edit-dialog">
      <div>
        <label class="mb-1 block text-sm font-medium" for="org-basic-info-name">
          {{ t('organization.basicInfoEdit.name') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText
          id="org-basic-info-name"
          v-model="form.name"
          class="w-full"
          data-testid="org-basic-info-name"
        />
        <p v-if="nameError" class="mt-1 text-sm text-red-600" role="alert">
          {{ nameError }}
        </p>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium" for="org-basic-info-name-kana">
          {{ t('organization.basicInfoEdit.nameKana') }}
        </label>
        <InputText id="org-basic-info-name-kana" v-model="form.nameKana" class="w-full" />
      </div>

      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium" for="org-basic-info-nickname1">
            {{ t('organization.basicInfoEdit.nickname1') }}
          </label>
          <InputText id="org-basic-info-nickname1" v-model="form.nickname1" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="org-basic-info-nickname2">
            {{ t('organization.basicInfoEdit.nickname2') }}
          </label>
          <InputText id="org-basic-info-nickname2" v-model="form.nickname2" class="w-full" />
        </div>
      </div>

      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('organization.basicInfoEdit.prefecture') }}
          </label>
          <Select
            v-model="selectedPref"
            :options="prefectures"
            option-label="name"
            :placeholder="t('organization.basicInfoEdit.selectPlaceholder')"
            filter
            show-clear
            class="w-full"
            data-testid="org-basic-info-prefecture"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('organization.basicInfoEdit.city') }}
          </label>
          <Select
            v-model="form.city"
            :options="cities"
            option-label="name"
            option-value="name"
            :loading="citiesLoading"
            :disabled="!selectedPref"
            :placeholder="t('organization.basicInfoEdit.selectPlaceholder')"
            filter
            show-clear
            class="w-full"
            data-testid="org-basic-info-city"
          />
        </div>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('button.cancel')"
        text
        :disabled="saving"
        @click="visible = false"
      />
      <Button
        :label="t('button.save')"
        icon="pi pi-check"
        :loading="saving"
        :disabled="!canSubmit"
        data-testid="org-basic-info-save"
        @click="handleSave"
      />
    </template>
  </Dialog>
</template>
