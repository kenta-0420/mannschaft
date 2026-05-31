<script setup lang="ts">
/**
 * F22.1 市（Market）— 札立てフォーム拡張コンポーネント
 *
 * 既存の RecruitmentListingForm に追加する地域選択 + 公開範囲 + フレンド宛先セレクタ。
 * ダッシュボード（チーム/組織）の「札を立てる」ダイアログ内に組み込む。
 *
 * 設計書: docs/features/F22.1_market/03_ui_i18n.md §4 / §4.2
 */
import type { FriendTargetInput } from '~/types/market'
import type { TeamFriendView } from '~/types/friends'
import type { TeamFriendFolderView } from '~/types/friendFolders'

interface Props {
  /** スコープタイプ */
  scopeType: 'TEAM' | 'ORGANIZATION'
  /** スコープID（チームID or 組織ID） */
  scopeId: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:prefectureCode': [value: string | null]
  'update:cityCode': [value: string | null]
  'update:visibility': [value: 'PUBLIC' | 'FRIEND_TEAMS_ONLY']
  'update:friendTargets': [value: FriendTargetInput[]]
}>()

const friendFoldersApi = useFriendFoldersApi()
const { handleApiError } = useErrorHandler()

// 地域選択
const {
  prefectures,
  cities,
  citiesLoading,
  selectedPrefecture,
  selectedCity,
  loadPrefectures,
  selectPrefecture,
} = useMarketRegions()

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

watch(selectedPrefecture, (v) => {
  emit('update:prefectureCode', v)
})

watch(selectedCity, (v) => {
  emit('update:cityCode', v)
})

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
    targets.push({ target_kind: 'ALL_FRIENDS' })
  }
  for (const fid of selectedFolderIds.value) {
    targets.push({ target_kind: 'FOLDER', folder_id: fid })
  }
  for (const tid of selectedTeamIds.value) {
    targets.push({ target_kind: 'TEAM', team_id: tid })
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
  try {
    folders.value = await friendFoldersApi.listFolders(props.scopeId)
  }
  catch (err) {
    handleApiError(err, 'フォルダ取得')
  }
  finally {
    foldersLoading.value = false
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

function addTeam(teamId: number) {
  if (!selectedTeamIds.value.includes(teamId)) {
    selectedTeamIds.value = [...selectedTeamIds.value, teamId]
  }
}

function removeTeam(teamId: number) {
  selectedTeamIds.value = selectedTeamIds.value.filter(id => id !== teamId)
}

onMounted(async () => {
  await loadPrefectures()
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
            @change="(e: { value: number }) => { if (e.value) addTeam(e.value) }"
          />
        </div>
      </div>
    </fieldset>
  </div>
</template>
