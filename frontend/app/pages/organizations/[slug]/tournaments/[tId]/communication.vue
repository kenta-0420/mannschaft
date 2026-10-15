<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'
import type { TournamentContactSpace, TournamentDivision } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { fetchContactSpaces, fetchDivisionContactSpaces, toggleVisibility, toggleDivisionVisibility } = useTournamentContact()
const { getDivisions } = useTournamentApi()
const { getChannel } = useChatApi()
const { showSuccess, showError } = useNotification()

/** アクティブタブ: 'bulletin'=掲示板 / 'chat'=チャット */
const activeTab = ref<'bulletin' | 'chat'>('bulletin')

/** アクティブスコープ: 'tournament'=大会全体 / divisionId */
const activeScope = ref<'tournament' | number>('tournament')

/** 大会レベルの連絡スペース */
const tournamentSpaces = ref<TournamentContactSpace[]>([])
/** ディビジョン別の連絡スペース（divisionId → spaces） */
const divisionSpacesMap = ref<Record<number, TournamentContactSpace[]>>({})
/** ディビジョン一覧 */
const divisions = ref<TournamentDivision[]>([])

/** チャット表示用チャンネル（channelId → ChatChannelResponse） */
const chatChannelCache = ref<Record<number, ChatChannelResponse>>({})

const loading = ref(true)
const spacesLoading = ref(false)
const chatLoading = ref(false)

// ===== アクティブなスペース取得 =====

const currentSpaces = computed<TournamentContactSpace[]>(() => {
  if (activeScope.value === 'tournament') return tournamentSpaces.value
  return divisionSpacesMap.value[activeScope.value as number] ?? []
})

const activeBulletinSpace = computed<TournamentContactSpace | null>(() => {
  return currentSpaces.value.find(s => s.bulletinRefId !== null) ?? null
})

const activeChatSpace = computed<TournamentContactSpace | null>(() => {
  return currentSpaces.value.find(s => s.chatChannelId !== null) ?? null
})

const activeChatChannel = computed<ChatChannelResponse | null>(() => {
  const channelId = activeChatSpace.value?.chatChannelId
  if (!channelId) return null
  return chatChannelCache.value[channelId] ?? null
})

// ===== データ取得 =====

async function loadTournamentSpaces() {
  spacesLoading.value = true
  try {
    tournamentSpaces.value = await fetchContactSpaces(tId)
  }
  catch {
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    spacesLoading.value = false
  }
}

async function loadDivisionSpaces(divisionId: number) {
  if (divisionSpacesMap.value[divisionId] !== undefined) return
  spacesLoading.value = true
  try {
    divisionSpacesMap.value[divisionId] = await fetchDivisionContactSpaces(tId, divisionId)
  }
  catch {
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    spacesLoading.value = false
  }
}

async function loadChatChannel(channelId: number) {
  if (chatChannelCache.value[channelId]) return
  chatLoading.value = true
  try {
    const res = await getChannel(channelId)
    chatChannelCache.value[channelId] = res.data
  }
  catch {
    showError(t('tournament.communication.load_failed'))
  }
  finally {
    chatLoading.value = false
  }
}

async function onScopeChange(scope: 'tournament' | number) {
  activeScope.value = scope
  if (scope !== 'tournament') {
    await loadDivisionSpaces(scope as number)
  }
  // チャットタブがアクティブなら対応チャンネルを先行ロード
  if (activeTab.value === 'chat') {
    await loadChatForCurrentScope()
  }
}

async function loadChatForCurrentScope() {
  const space = activeChatSpace.value
  if (space?.chatChannelId) {
    await loadChatChannel(space.chatChannelId)
  }
}

// ===== 公開設定トグル =====

async function onToggleVisibility(space: TournamentContactSpace, newValue: boolean) {
  try {
    if (activeScope.value === 'tournament') {
      await toggleVisibility(tId, space.id, newValue)
      // ローカル状態を更新
      const idx = tournamentSpaces.value.findIndex(s => s.id === space.id)
      if (idx !== -1) {
        const existing = tournamentSpaces.value[idx]!
        tournamentSpaces.value[idx] = { ...existing, id: existing.id, isPublic: newValue }
      }
    }
    else {
      const divId = activeScope.value as number
      await toggleDivisionVisibility(tId, divId, space.id, newValue)
      const spaces = divisionSpacesMap.value[divId] ?? []
      const idx = spaces.findIndex(s => s.id === space.id)
      if (idx !== -1) {
        divisionSpacesMap.value[divId] = spaces.map((s, i) => i === idx ? { ...s, isPublic: newValue } : s)
      }
    }
    showSuccess(t('tournament.communication.visibility_saved'))
  }
  catch {
    showError(t('tournament.communication.visibility_failed'))
  }
}

// ===== ウォッチ =====

watch(activeTab, async (tab) => {
  if (tab === 'chat') {
    await loadChatForCurrentScope()
  }
})

onMounted(async () => {
  try {
    // 権限・連絡スペース・ディビジョン一覧は失敗を独立に扱う。
    // Promise.all でまとめると「どれか1つでも失敗＝全部失敗」に粒度が粗くなり、
    // 取得できているものまで捨てたり、無関係な失敗文言を出したりする（Issue #2770）。
    // なお loadTournamentSpaces は自前で失敗を通知するため、ここでは結果を見ない。
    const [permResult, , divResult] = await Promise.allSettled([
      loadPermissions(),
      loadTournamentSpaces(),
      // 取得失敗を空配列に偽装せず、失敗として表面化させる
      getDivisions(orgSlug, tId),
    ])

    // loadPermissions は失敗を throw ではなく戻り値（{ ok: false }）で返す契約のため、
    // allSettled の rejected だけを見ていると取得失敗を取りこぼし、
    // 通知が出ないまま管理操作だけが無言で無効化される。両方を失敗として扱う。
    const permissionsFailed = permResult.status === 'rejected' || !permResult.value.ok
    if (permissionsFailed) {
      showError(t('tournament.communication.permissions_load_failed'))
    }

    if (divResult.status === 'fulfilled') {
      divisions.value = divResult.value.data
    }
    else {
      showError(t('tournament.communication.divisions_load_failed'))
    }
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader :title="$t('tournament.communication.title')" :back-to="`/organizations/${orgSlug}/tournaments/${tId}`" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <!-- スコープ切替（大会全体 / ディビジョン別） -->
      <div class="mb-4 flex flex-wrap gap-2">
        <Button
          :label="$t('tournament.communication.tournament_space')"
          :outlined="activeScope !== 'tournament'"
          size="small"
          @click="onScopeChange('tournament')"
        />
        <Button
          v-for="div in divisions"
          :key="div.id"
          :label="div.name ?? $t('tournament.communication.division_space')"
          :outlined="activeScope !== div.id"
          size="small"
          @click="onScopeChange(div.id)"
        />
      </div>

      <!-- 公開設定（主催者ADMINのみ） -->
      <div v-if="isAdminOrDeputy && currentSpaces.length > 0" class="mb-4 flex flex-wrap gap-4">
        <div
          v-for="space in currentSpaces"
          :key="space.id"
          class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-sm"
        >
          <span class="text-surface-600">{{ $t('tournament.communication.visibility_toggle') }}:</span>
          <ToggleSwitch
            :model-value="space.isPublic"
            @update:model-value="(v: boolean) => onToggleVisibility(space, v)"
          />
          <span class="text-xs" :class="space.isPublic ? 'text-green-600' : 'text-surface-400'">
            {{ space.isPublic ? $t('tournament.communication.public') : $t('tournament.communication.private') }}
          </span>
        </div>
      </div>

      <!-- 掲示板 / チャット タブ切替 -->
      <div class="mb-4 flex gap-2 border-b border-surface-200 dark:border-surface-700">
        <button
          type="button"
          class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
          :class="activeTab === 'bulletin' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
          @click="activeTab = 'bulletin'"
        >
          <i class="pi pi-clipboard mr-1" />{{ $t('tournament.communication.bulletin') }}
        </button>
        <button
          type="button"
          class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
          :class="activeTab === 'chat' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
          @click="activeTab = 'chat'"
        >
          <i class="pi pi-comments mr-1" />{{ $t('tournament.communication.chat') }}
        </button>
      </div>

      <!-- スペースがない場合 -->
      <DashboardEmptyState
        v-if="!spacesLoading && currentSpaces.length === 0"
        icon="pi pi-inbox"
        :message="$t('tournament.communication.empty')"
      />

      <template v-else>
        <!-- 掲示板タブ -->
        <template v-if="activeTab === 'bulletin'">
          <div v-if="spacesLoading" class="flex justify-center py-8">
            <LoadingBounce />
          </div>
          <template v-else-if="activeBulletinSpace">
            <BulletinThreadList
              scope-type="TOURNAMENT"
              :scope-id="activeBulletinSpace.bulletinRefId!"
              :can-manage="isAdminOrDeputy"
            />
          </template>
          <DashboardEmptyState
            v-else
            icon="pi pi-clipboard"
            :message="$t('tournament.communication.no_bulletin')"
          />
        </template>

        <!-- チャットタブ -->
        <template v-if="activeTab === 'chat'">
          <div v-if="spacesLoading || chatLoading" class="flex justify-center py-8">
            <LoadingBounce />
          </div>
          <template v-else-if="activeChatChannel">
            <div class="h-[calc(100vh-18rem)] overflow-hidden rounded-xl border border-surface-300">
              <ChatMessagePanel
                :channel="activeChatChannel"
                :can-pin="isAdminOrDeputy"
                :can-delete="isAdminOrDeputy"
                :organization-id="orgSlug"
              />
            </div>
          </template>
          <DashboardEmptyState
            v-else
            icon="pi pi-comments"
            :message="$t('tournament.communication.no_chat')"
          />
        </template>
      </template>
    </template>
  </div>
</template>
