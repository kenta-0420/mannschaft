<script setup lang="ts">
import dayjs from 'dayjs'
import type { TournamentResponse, TournamentSport } from '~/types/tournament'
import { TOURNAMENT_SPORTS } from '~/types/tournament'
import {
  TOURNAMENT_VISIBILITY_LEVELS,
  type TournamentVisibility,
} from '~/utils/tournamentStandings'

definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.slug)

const { t } = useI18n()
const notification = useNotification()
const { getTournaments, createTournament } = useTournamentApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
const { userTimezone } = useDatetime()

/** 競技 8 種のセレクタ選択肢（生成型 TournamentSport から導出・ラベルは i18n）。 */
const sportOptions = computed(() =>
  TOURNAMENT_SPORTS.map((sport) => ({
    value: sport,
    label: t(`tournament.sport.options.${sport}`),
  })),
)

/** 可視性 6 レベルのセレクタ選択肢（ラベル＋説明は i18n）。 */
const visibilityOptions = computed(() =>
  TOURNAMENT_VISIBILITY_LEVELS.map((level) => ({
    value: level,
    label: t(`tournament.visibility.levels.${level}.label`),
    description: t(`tournament.visibility.levels.${level}.description`),
  })),
)

const tournaments = ref<TournamentResponse[]>([])
const loading = ref(false)

const showCreateDialog = ref(false)
const saving = ref(false)
const form = ref({
  title: '',
  sportCategory: '',
  sport: 'SOCCER' as TournamentSport,
  format: 'LEAGUE' as 'LEAGUE' | 'KNOCKOUT' | 'GROUP_KNOCKOUT',
  seasonYear: dayjs().tz(userTimezone.value).year(),
  isPublic: false,
  visibility: 'PUBLIC' as TournamentVisibility,
  description: '',
  winPoints: 3,
  drawPoints: 1,
  lossPoints: 0,
})

async function load() {
  loading.value = true
  try {
    const res = await getTournaments(orgId)
    tournaments.value = res.data
  } catch {
    notification.error('大会一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(s: string): string {
  switch (s) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600'
    case 'OPEN':
      return 'bg-green-100 text-green-700'
    case 'IN_PROGRESS':
      return 'bg-blue-100 text-blue-700'
    case 'COMPLETED':
      return 'bg-purple-100 text-purple-700'
    default:
      return 'bg-surface-100'
  }
}

function getFormatLabel(f: string): string {
  switch (f) {
    case 'LEAGUE':
      return 'リーグ戦'
    case 'KNOCKOUT':
      return 'トーナメント'
    case 'GROUP_KNOCKOUT':
      return 'グループ+トーナメント'
    default:
      return f
  }
}

function openCreateDialog() {
  form.value = {
    title: '',
    sportCategory: '',
    sport: 'SOCCER',
    format: 'LEAGUE',
    seasonYear: dayjs().tz(userTimezone.value).year(),
    isPublic: false,
    visibility: 'PUBLIC',
    description: '',
    winPoints: 3,
    drawPoints: 1,
    lossPoints: 0,
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.title.trim() || !form.value.sportCategory.trim()) return
  saving.value = true
  try {
    await createTournament(orgId, {
      title: form.value.title,
      sportCategory: form.value.sportCategory,
      sport: form.value.sport,
      format: form.value.format,
      seasonYear: form.value.seasonYear,
      isPublic: form.value.isPublic,
      visibility: form.value.visibility,
      description: form.value.description || undefined,
      winPoints: form.value.winPoints,
      drawPoints: form.value.drawPoints,
      lossPoints: form.value.lossPoints,
    })
    notification.success('大会を作成しました')
    showCreateDialog.value = false
    await load()
  } catch {
    notification.error('大会の作成に失敗しました')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadPermissions()
  load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="大会・リーグ" />
      <Button label="大会を作成" icon="pi pi-plus" size="small" @click="openCreateDialog" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2">
      <NuxtLink
        v-for="tour in tournaments"
        :key="tour.id"
        :to="`/organizations/${orgId}/tournaments/${tour.id}`"
        class="block rounded-xl border border-surface-300 bg-surface-0 p-4 transition hover:border-primary-400 hover:shadow-sm"
      >
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(tour.structure?.status ?? '')" class="rounded px-2 py-0.5 text-xs font-medium">{{
            tour.structure?.status
          }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{
            getFormatLabel(tour.content?.format ?? '')
          }}</span>
        </div>
        <h3 class="text-sm font-semibold">{{ tour.content?.name }}</h3>
        <div class="mt-2 flex items-center gap-3 text-xs text-surface-400">
          <span v-if="tour.content?.season">{{ tour.content.season }}</span>
          <span>勝{{ tour.scoring?.winPoints }} 分{{ tour.scoring?.drawPoints }} 負{{ tour.scoring?.lossPoints }}</span>
        </div>
      </NuxtLink>
      <DashboardEmptyState v-if="tournaments.length === 0" class="col-span-full" icon="pi pi-trophy" message="大会がありません" />
    </div>

    <Dialog v-model:visible="showCreateDialog" modal header="大会を作成" :style="{ width: '30rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">大会名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.title" placeholder="例: 2026年度春季リーグ" class="w-full" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">競技種目 <span class="text-red-500">*</span></label>
            <InputText v-model="form.sportCategory" placeholder="例: サッカー" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">年度 <span class="text-red-500">*</span></label>
            <InputNumber v-model="form.seasonYear" :min="2000" :max="2100" class="w-full" />
          </div>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.sport.label') }}</label>
          <Select
            v-model="form.sport"
            :options="sportOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="create-sport-select"
          />
          <p class="text-xs text-surface-500">{{ $t('tournament.sport.help') }}</p>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">形式 <span class="text-red-500">*</span></label>
          <Select
            v-model="form.format"
            :options="[
              { label: 'リーグ戦', value: 'LEAGUE' },
              { label: 'トーナメント', value: 'KNOCKOUT' },
              { label: 'グループ+トーナメント', value: 'GROUP_KNOCKOUT' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="2" class="w-full" />
        </div>
        <div v-if="isAdminOrDeputy" class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('tournament.visibility.title') }}</label>
          <Select
            v-model="form.visibility"
            :options="visibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="create-visibility-select"
          >
            <template #option="{ option }">
              <div class="flex flex-col">
                <span class="font-medium">{{ option.label }}</span>
                <span class="text-xs text-surface-500">{{ option.description }}</span>
              </div>
            </template>
          </Select>
          <p class="text-xs text-surface-500">
            {{ $t(`tournament.visibility.levels.${form.visibility}.description`) }}
          </p>
        </div>
        <div class="grid grid-cols-3 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">勝点</label>
            <InputNumber v-model="form.winPoints" :min="0" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">引分点</label>
            <InputNumber v-model="form.drawPoints" :min="0" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">敗点</label>
            <InputNumber v-model="form.lossPoints" :min="0" class="w-full" />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isPublic" :binary="true" input-id="public" />
          <label for="public" class="text-sm">公開大会にする</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text :disabled="saving" @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
