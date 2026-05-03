<script setup lang="ts">
import type {
  AvailabilityDefaultRequest,
  AvailabilityDefaultResponse,
  ShiftPreference,
} from '~/types/shift'
import { preferenceToColor, preferenceToI18nKey } from '~/utils/shiftPreference'

/**
 * F03.5 デフォルト可否プロファイル設定ページ
 *
 * 曜日ごとにデフォルトの希望強度を設定する。
 * 希望提出フォームの初期値として使用される。
 */

definePageMeta({ middleware: 'auth' })

const { t, locale } = useI18n()
const { error: showError, success: showSuccess } = useNotification()
const { getAvailabilityDefaults, setAvailabilityDefaults, deleteAvailabilityDefaults } =
  useShiftAvailabilityDefaultApi()
const teamStore = useTeamStore()

const loading = ref(false)
const saving = ref(false)
const selectedTeamId = ref<number | null>(null)

// 曜日 0(日)〜6(土) — toLocaleDateString で動的生成。locale 変更に追従させる。
const DOW_LABELS = computed(() =>
  Array.from({ length: 7 }, (_, i) => {
    // 2024-01-07(日)を起点に i 日加算
    const d = new Date(2024, 0, 7 + i)
    return d.toLocaleDateString(locale.value, { weekday: 'short' })
  }),
)

const preferenceOptions: ShiftPreference[] = [
  'PREFERRED',
  'AVAILABLE',
  'WEAK_REST',
  'STRONG_REST',
  'ABSOLUTE_REST',
]

const preferences = ref<Map<number, ShiftPreference>>(new Map())

function initDefaults(current: AvailabilityDefaultResponse[]) {
  const map = new Map<number, ShiftPreference>()
  for (let i = 0; i <= 6; i++) {
    map.set(i, 'AVAILABLE')
  }
  for (const d of current) {
    map.set(d.dayOfWeek, d.preference)
  }
  preferences.value = map
}

async function loadForTeam(id: number) {
  loading.value = true
  try {
    const data = await getAvailabilityDefaults(id)
    initDefaults(data)
  } catch {
    showError(t('shift.notification.errorLoad'))
    initDefaults([])
  } finally {
    loading.value = false
  }
}

function setDowPreference(dow: number, pref: ShiftPreference) {
  const next = new Map(preferences.value)
  next.set(dow, pref)
  preferences.value = next
}

async function save() {
  if (!selectedTeamId.value) return
  saving.value = true
  try {
    const availabilities: AvailabilityDefaultRequest[] = []
    for (const [dow, pref] of preferences.value.entries()) {
      availabilities.push({
        dayOfWeek: dow,
        startTime: '00:00',
        endTime: '23:59',
        preference: pref,
      })
    }
    await setAvailabilityDefaults(selectedTeamId.value, { availabilities })
    showSuccess(t('shift.notification.updateSuccess'))
  } catch {
    showError(t('shift.notification.errorUpdate'))
  } finally {
    saving.value = false
  }
}

async function resetAll() {
  if (!selectedTeamId.value) return
  try {
    await deleteAvailabilityDefaults(selectedTeamId.value)
    initDefaults([])
    showSuccess(t('shift.notification.deleteSuccess'))
  } catch {
    showError(t('shift.notification.errorDelete'))
  }
}

function cardClass(pref: ShiftPreference, selected: boolean): string {
  const base =
    'flex-1 min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg border-2 text-xs font-medium cursor-pointer transition-all text-center leading-tight px-1 py-1'
  const color = preferenceToColor(pref)
  const active = selected
    ? 'ring-2 ring-primary ring-offset-1 scale-105 shadow-sm'
    : 'opacity-60 hover:opacity-90'
  return [base, color, active].join(' ')
}

async function onTeamSelect(id: number) {
  selectedTeamId.value = id
  await loadForTeam(id)
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  // チームが1つなら自動選択
  if (teamStore.myTeams.length === 1) {
    await onTeamSelect(teamStore.myTeams[0]!.id)
  }
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('shift.page.availability')" />

    <p class="mb-4 text-sm text-surface-500">
      {{ t('shift.availabilityDescription') }}
    </p>

    <!-- チームが複数の場合は選択 -->
    <template v-if="selectedTeamId === null">
      <PageLoading v-if="teamStore.loading" size="40px" />
      <div v-else class="flex flex-col gap-3">
        <DashboardEmptyState
          v-if="teamStore.myTeams.length === 0"
          icon="pi-users"
          :message="t('shift.empty.noConstraints')"
        />
        <SectionCard
          v-for="team in teamStore.myTeams"
          :key="team.id"
          class="cursor-pointer transition-shadow hover:shadow-md"
          @click="onTeamSelect(team.id)"
        >
          <div class="flex items-center gap-3">
            <div
              class="flex h-10 w-10 items-center justify-center rounded-full bg-surface-200 text-sm font-bold text-surface-600"
            >
              {{ team.name.charAt(0) }}
            </div>
            <p class="text-sm font-semibold text-surface-800">{{ team.name }}</p>
          </div>
        </SectionCard>
      </div>
    </template>

    <template v-else>
      <!-- チーム変更ボタン（複数チームの場合） -->
      <div v-if="teamStore.myTeams.length > 1" class="mb-4">
        <Button
          icon="pi pi-arrow-left"
          :label="t('common.button.back')"
          text
          severity="secondary"
          @click="selectedTeamId = null"
        />
      </div>

      <PageLoading v-if="loading" size="40px" />

      <template v-else>
        <div class="flex flex-col gap-4">
          <div
            v-for="(label, idx) in DOW_LABELS"
            :key="idx"
            class="rounded-xl border border-surface-200 bg-surface-0 p-4"
          >
            <!-- 曜日ラベル -->
            <div class="mb-3 flex items-center gap-2">
              <span
                class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
                :class="
                  idx === 0 || idx === 6
                    ? 'bg-red-100 text-red-600'
                    : 'bg-surface-100 text-surface-700'
                "
              >
                {{ label }}
              </span>
              <span class="text-xs text-surface-500">
                {{ preferences.get(idx) ? t(preferenceToI18nKey(preferences.get(idx)!)) : '—' }}
              </span>
            </div>

            <!-- 5段階ラジオ -->
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="pref in preferenceOptions"
                :key="pref"
                type="button"
                :class="cardClass(pref, preferences.get(idx) === pref)"
                @click="setDowPreference(idx, pref)"
              >
                {{ t(preferenceToI18nKey(pref)) }}
              </button>
            </div>
          </div>
        </div>

        <!-- 凡例 -->
        <div class="mt-4 rounded-xl border border-surface-200 bg-surface-0 p-4">
          <h4 class="mb-2 text-xs font-semibold text-surface-500">{{ t('shift.legend') }}</h4>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="pref in preferenceOptions"
              :key="pref"
              class="rounded-full px-2 py-0.5 text-xs"
              :class="preferenceToColor(pref)"
            >
              {{ t(preferenceToI18nKey(pref)) }}
            </span>
          </div>
        </div>

        <!-- アクションボタン -->
        <div class="mt-6 flex flex-wrap items-center justify-between gap-3">
          <Button
            :label="t('shift.action.reset')"
            icon="pi pi-refresh"
            text
            severity="secondary"
            @click="resetAll"
          />
          <Button
            :label="t('common.button.save')"
            icon="pi pi-check"
            :loading="saving"
            @click="save"
          />
        </div>
      </template>
    </template>
  </div>
</template>
