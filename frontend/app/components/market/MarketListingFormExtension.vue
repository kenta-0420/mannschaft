<script setup lang="ts">
/**
 * F22.1 市（Market）— 札立てフォーム拡張コンポーネント
 *
 * 既存の RecruitmentListingForm に追加する地域選択 + 公開範囲 + フレンド宛先セレクタ。
 * ダッシュボード（チーム/組織）の「札を立てる」ダイアログ内に組み込む。
 *
 * 設計書: docs/features/F22.1_market/03_ui_i18n.md §4 / §4.2
 */
import type { FriendTargetInput, RegionInput } from '~/types/market'
import type { TeamFriendView } from '~/types/friends'
import type { TeamFriendFolderView } from '~/types/friendFolders'

/** 選択済み地域の表示モデル（チップ表示用に名前を保持・emit はコードのみ）。 */
interface SelectedRegion {
  prefectureCode: string
  prefectureName: string
  cityCode: string | null
  cityName: string | null
}

interface Props {
  /** スコープタイプ */
  scopeType: 'TEAM' | 'ORGANIZATION'
  /** スコープID（チームID or 組織ID） */
  scopeId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  // 後方互換: 代表（先頭）地域の単一 emit。BE は regions 未指定時にこれを 1 件として扱う。
  'update:prefectureCode': [value: string | null]
  'update:cityCode': [value: string | null]
  // F22.1 Phase2 D: 複数地域募集（N:N）。選択済み地域ペアを全件 emit する。
  'update:regions': [value: RegionInput[]]
  'update:visibility': [value: 'PUBLIC' | 'FRIEND_TEAMS_ONLY']
  'update:friendTargets': [value: FriendTargetInput[]]
}>()

const friendFoldersApi = useFriendFoldersApi()
const friendTeamsApi = useFriendTeamsApi()
const teamApi = useTeamApi()
const { handleApiError } = useErrorHandler()

// 地域選択
const {
  prefectures,
  cities,
  citiesLoading,
  selectedPrefecture,
  selectedCity,
  loadPrefectures,
  loadCities,
  selectPrefecture,
} = useMarketRegions()

// 複数地域募集（N:N・F22.1 Phase2 D）: 追加済みの地域ペア。
const selectedRegions = ref<SelectedRegion[]>([])

// 公開範囲
const visibility = ref<'PUBLIC' | 'FRIEND_TEAMS_ONLY'>('PUBLIC')

// フレンド宛先
const targetAllFriends = ref(false)
const selectedFolderIds = ref<number[]>([])
const selectedTeamIds = ref<number[]>([])

// フレンドフォルダ一覧（チームのみ）
const folders = ref<TeamFriendFolderView[]>([])
const foldersLoading = ref(false)

// フレンドチーム一覧（個別指定用）
const friends = ref<TeamFriendView[]>([])
const friendsLoading = ref(false)

// computed: 選択中フレンドフォルダ（重複除外）
const availableFolderOptions = computed(() =>
  folders.value.filter(f => !selectedFolderIds.value.includes(f.id)),
)

// computed: 選択中フレンドチーム（重複除外・已選択除外）
const availableFriendOptions = computed(() =>
  friends.value.filter(f => !selectedTeamIds.value.includes(f.friendTeamId)),
)

// =====================================================================
// emit helpers
// =====================================================================

// 選択済み地域が変わるたびに regions[]（全件）と代表地域（先頭・後方互換）を emit する。
watch(selectedRegions, (regions) => {
  emit('update:regions', regions.map(r => ({
    prefectureCode: r.prefectureCode,
    cityCode: r.cityCode,
  })))
  const representative = regions[0] ?? null
  emit('update:prefectureCode', representative?.prefectureCode ?? null)
  emit('update:cityCode', representative?.cityCode ?? null)
}, { deep: true })

/** チップ表示用ラベル（市区町村が空なら県のみ）。 */
function regionLabel(r: SelectedRegion): string {
  return r.cityName ? `${r.prefectureName} ${r.cityName}` : r.prefectureName
}

/** チップの一意キー（県＋市）。 */
function regionKey(r: SelectedRegion): string {
  return `${r.prefectureCode}-${r.cityCode ?? ''}`
}

/**
 * 現在の都道府県／市区町村 Select の選択を地域ペアとして追加する。
 * 都道府県未選択時・同一ペア重複時は追加しない。
 */
function addCurrentRegion() {
  const prefCode = selectedPrefecture.value
  if (!prefCode) return
  const cityCode = selectedCity.value ?? null
  const exists = selectedRegions.value.some(
    r => r.prefectureCode === prefCode && r.cityCode === cityCode,
  )
  if (exists) return
  const prefectureName = prefectures.value.find(p => p.code === prefCode)?.name ?? prefCode
  const cityName = cityCode
    ? (cities.value.find(c => c.code === cityCode)?.name ?? cityCode)
    : null
  selectedRegions.value = [
    ...selectedRegions.value,
    { prefectureCode: prefCode, prefectureName, cityCode, cityName },
  ]
}

/** 追加済み地域を削除する。 */
function removeRegion(key: string) {
  selectedRegions.value = selectedRegions.value.filter(r => regionKey(r) !== key)
}

watch(visibility, (v) => {
  emit('update:visibility', v)
  if (v === 'PUBLIC') {
    targetAllFriends.value = false
    selectedFolderIds.value = []
    selectedTeamIds.value = []
  }
})

watch([targetAllFriends, selectedFolderIds, selectedTeamIds], () => {
  const targets: FriendTargetInput[] = []
  if (targetAllFriends.value) {
    targets.push({ targetKind: 'ALL_FRIENDS' })
  }
  for (const fid of selectedFolderIds.value) {
    targets.push({ targetKind: 'FOLDER', folderId: fid })
  }
  for (const tid of selectedTeamIds.value) {
    targets.push({ targetKind: 'TEAM', teamId: tid })
  }
  emit('update:friendTargets', targets)
}, { deep: true })

// =====================================================================
// 地域選択ハンドラ
// =====================================================================

async function onPrefectureChange(code: string | null) {
  await selectPrefecture(code)
}

// =====================================================================
// フレンドフォルダ・フレンドチーム取得（チームのみ）
// =====================================================================

async function loadFoldersAndFriends() {
  if (props.scopeType !== 'TEAM') return

  foldersLoading.value = true
  friendsLoading.value = true
  try {
    const [foldersResult, friendsResult] = await Promise.all([
      friendFoldersApi.listFolders(props.scopeId),
      friendTeamsApi.listFriends(props.scopeId),
    ])
    folders.value = foldersResult
    friends.value = friendsResult.data
  }
  catch (err) {
    handleApiError(err, 'フォルダ・フレンドチーム取得')
  }
  finally {
    foldersLoading.value = false
    friendsLoading.value = false
  }
}

function addFolder(folderId: number) {
  if (!selectedFolderIds.value.includes(folderId)) {
    selectedFolderIds.value = [...selectedFolderIds.value, folderId]
  }
}

function removeFolder(folderId: number) {
  selectedFolderIds.value = selectedFolderIds.value.filter(id => id !== folderId)
}

function addTeam(teamId: number | string) {
  const id = typeof teamId === 'string' ? Number(teamId) : teamId
  if (!selectedTeamIds.value.includes(id)) {
    selectedTeamIds.value = [...selectedTeamIds.value, id]
  }
}

function removeTeam(teamId: number | string) {
  const id = typeof teamId === 'string' ? Number(teamId) : teamId
  selectedTeamIds.value = selectedTeamIds.value.filter(i => i !== id)
}

/**
 * F22.1 Phase2 足場C 第三陣: scope=TEAM のとき team の地域コードを初期値としてプリフィルする。
 *
 * BE 側（C第二陣 RecruitmentListingService）でも request 未指定なら team 地域を補完するが、
 * 入力前に画面で見えるよう FE でも初期表示する。ユーザーは上書き可能。
 * team の `location.prefectureCode`/`cityCode`（BE TeamResponse.TeamLocationDto camelCase）を読む。
 */
async function prefillTeamRegion() {
  if (props.scopeType !== 'TEAM') return
  try {
    const res = await teamApi.getTeam(props.scopeId)
    const prefCode = res.data?.location?.prefectureCode ?? null
    const cityCode = res.data?.location?.cityCode ?? null
    if (!prefCode) return
    // 都道府県を初期選択。
    selectedPrefecture.value = prefCode
    // 配下市区町村をロードしてから市区町村を初期選択（selectPrefecture は city をリセットするため使わない）。
    await loadCities(prefCode)
    if (cityCode) {
      selectedCity.value = cityCode
    }
    // team 地域を初期の選択済み地域として 1 件追加する（ユーザーは削除・追加可能）。
    addCurrentRegion()
  } catch {
    // team 取得失敗時はプリフィルしないだけ（ユーザーが手動選択可能）。
  }
}

onMounted(async () => {
  await loadPrefectures()
  await prefillTeamRegion()
  if (visibility.value === 'FRIEND_TEAMS_ONLY') {
    await loadFoldersAndFriends()
  }
})

watch(visibility, async (v) => {
  if (v === 'FRIEND_TEAMS_ONLY' && folders.value.length === 0) {
    await loadFoldersAndFriends()
  }
})
</script>

<template>
  <div class="flex flex-col gap-4" data-testid="market-form-extension">
    <!-- 地域選択（都道府県 → 市区町村） -->
    <fieldset class="rounded border border-surface-300 p-3">
      <legend class="px-1 text-sm font-semibold text-surface-600">
        {{ $t('market.filter.prefecture') }} / {{ $t('market.filter.city') }}
      </legend>
      <div class="mt-2 flex flex-wrap gap-3">
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500" :aria-label="$t('market.filter.prefecture')">
            {{ $t('market.filter.prefecture') }}
          </label>
          <Select
            v-model="selectedPrefecture"
            :options="prefectures"
            option-label="name"
            option-value="code"
            :placeholder="$t('market.filter.allPrefectures')"
            show-clear
            class="w-44"
            @change="(e: { value: string | null }) => onPrefectureChange(e.value)"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">
            {{ $t('market.filter.city') }}
          </label>
          <Select
            v-model="selectedCity"
            :options="cities"
            option-label="name"
            option-value="code"
            :placeholder="$t('market.filter.allCities')"
            show-clear
            :disabled="!selectedPrefecture || citiesLoading"
            :loading="citiesLoading"
            class="w-48"
          />
        </div>
        <div class="flex flex-col justify-end gap-1">
          <Button
            type="button"
            :label="$t('market.region.add')"
            icon="pi pi-plus"
            severity="secondary"
            outlined
            size="small"
            :disabled="!selectedPrefecture"
            data-testid="market-region-add"
            @click="addCurrentRegion"
          />
        </div>
      </div>

      <!-- 追加済み地域チップ（複数地域募集 N:N・F22.1 Phase2 D） -->
      <div class="mt-3 flex flex-col gap-1">
        <p class="text-xs text-surface-500">
          {{ $t('market.region.selected') }}
        </p>
        <div
          v-if="selectedRegions.length > 0"
          class="flex flex-wrap gap-1"
          data-testid="market-region-chips"
        >
          <Chip
            v-for="r in selectedRegions"
            :key="regionKey(r)"
            :label="regionLabel(r)"
            removable
            @remove="removeRegion(regionKey(r))"
          />
        </div>
        <p v-else class="text-xs text-surface-400" data-testid="market-region-none">
          {{ $t('market.region.none') }}
        </p>
      </div>
    </fieldset>

    <!-- 公開範囲 -->
    <div class="flex flex-col gap-2">
      <label class="text-sm font-semibold text-surface-600">
        {{ $t('market.visibility.label') }}
      </label>
      <SelectButton
        v-model="visibility"
        :options="[
          { value: 'PUBLIC', label: $t('market.visibility.public') },
          { value: 'FRIEND_TEAMS_ONLY', label: $t('market.visibility.friendsOnly') },
        ]"
        option-label="label"
        option-value="value"
        :aria-label="$t('market.visibility.label')"
        data-testid="market-visibility-selector"
      />
    </div>

    <!-- フレンド宛先セレクタ（FRIEND_TEAMS_ONLY のときのみ表示） -->
    <fieldset
      v-if="visibility === 'FRIEND_TEAMS_ONLY'"
      class="rounded border border-primary-300 p-3"
      data-testid="market-friend-target-selector"
    >
      <legend class="px-1 text-sm font-semibold text-primary-600">
        {{ $t('market.friendTarget.allFriends') }}
      </legend>
      <div class="mt-2 flex flex-col gap-3">
        <!-- 全成立フレンド -->
        <div class="flex items-center gap-2">
          <Checkbox
            v-model="targetAllFriends"
            input-id="targetAllFriends"
            :binary="true"
          />
          <label for="targetAllFriends" class="text-sm">
            {{ $t('market.friendTarget.allFriends') }}（ALL_FRIENDS）
          </label>
        </div>

        <!-- フォルダ指定（チームのみ・フォルダが存在する場合） -->
        <div v-if="scopeType === 'TEAM'" class="flex flex-col gap-2">
          <p class="text-sm font-medium text-surface-600">
            {{ $t('market.friendTarget.folder') }}
          </p>
          <div v-if="foldersLoading" class="text-sm text-surface-400">
            <i class="pi pi-spin pi-spinner" />
          </div>
          <template v-else-if="folders.length === 0">
            <p class="text-xs text-surface-400">
              {{ $t('market.friendTarget.noFolders') }}
            </p>
          </template>
          <template v-else>
            <!-- 選択済みフォルダチップ -->
            <div v-if="selectedFolderIds.length > 0" class="flex flex-wrap gap-1">
              <Chip
                v-for="fid in selectedFolderIds"
                :key="fid"
                :label="folders.find(f => f.id === fid)?.name ?? String(fid)"
                removable
                @remove="removeFolder(fid)"
              />
            </div>
            <!-- フォルダ追加 -->
            <Select
              :options="availableFolderOptions"
              option-label="name"
              option-value="id"
              :placeholder="$t('market.friendTarget.selectFolder')"
              class="w-full"
              @change="(e: { value: number }) => { if (e.value) addFolder(e.value) }"
            />
          </template>
        </div>

        <!-- 個別チーム指定 -->
        <div class="flex flex-col gap-2">
          <p class="text-sm font-medium text-surface-600">
            {{ $t('market.friendTarget.team') }}
          </p>
          <!-- 選択済みチームチップ -->
          <div v-if="selectedTeamIds.length > 0" class="flex flex-wrap gap-1">
            <Chip
              v-for="tid in selectedTeamIds"
              :key="tid"
              :label="friends.find(f => f.friendTeamId === tid)?.friendTeamName ?? String(tid)"
              removable
              @remove="removeTeam(tid)"
            />
          </div>
          <!-- フレンドチーム追加 -->
          <div v-if="friendsLoading" class="text-sm text-surface-400">
            <i class="pi pi-spin pi-spinner" />
          </div>
          <Select
            v-else
            :options="availableFriendOptions"
            option-label="friendTeamName"
            option-value="friendTeamId"
            :placeholder="$t('market.friendTarget.addTeam')"
            class="w-full"
            :disabled="availableFriendOptions.length === 0"
            @change="(e: { value: number | string }) => { if (e.value) addTeam(String(e.value)) }"
          />
        </div>
      </div>
    </fieldset>
  </div>
</template>
