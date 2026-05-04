<script setup lang="ts">
import type {
  PersonalTimetable,
  PersonalWeeklyView,
} from '~/types/personal-timetable'
import type { DayOfWeekKey } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const api = useMyPersonalTimetableApi()
const { error } = useNotification()

const id = computed(() => Number(route.params.id))

const detail = ref<PersonalTimetable | null>(null)
const weekly = ref<PersonalWeeklyView | null>(null)
const loading = ref(true)
const weekOf = ref<string>(new Date().toISOString().slice(0, 10))

const DAY_KEYS: DayOfWeekKey[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

const dayLabels = computed(() => ({
  MON: t('personalTimetable.day_mon'),
  TUE: t('personalTimetable.day_tue'),
  WED: t('personalTimetable.day_wed'),
  THU: t('personalTimetable.day_thu'),
  FRI: t('personalTimetable.day_fri'),
  SAT: t('personalTimetable.day_sat'),
  SUN: t('personalTimetable.day_sun'),
} as Record<DayOfWeekKey, string>))

async function load() {
  loading.value = true
  try {
    const [d, w] = await Promise.all([
      api.get(id.value),
      api.getWeekly(id.value, weekOf.value),
    ])
    detail.value = d
    weekly.value = w
  }
  catch (e) {
    error(t('personalTimetable.load_error'), String(e))
  }
  finally {
    loading.value = false
  }
}

function shiftWeek(deltaDays: number) {
  const d = new Date(weekOf.value)
  d.setDate(d.getDate() + deltaDays)
  weekOf.value = d.toISOString().slice(0, 10)
  void load()
}

function goToday() {
  weekOf.value = new Date().toISOString().slice(0, 10)
  void load()
}

function statusLabel(s: string | undefined) {
  if (!s) return ''
  return (
    {
      DRAFT: t('personalTimetable.status_draft'),
      ACTIVE: t('personalTimetable.status_active'),
      ARCHIVED: t('personalTimetable.status_archived'),
    }[s] ?? s
  )
}

onMounted(load)
</script>

<template>
  <div class="p-4 md:p-6 max-w-6xl mx-auto">
    <div class="mb-4">
      <NuxtLink to="/me/personal-timetable" class="text-sm text-blue-600 hover:underline">
        ← {{ t('personalTimetable.btn_back_to_list') }}
      </NuxtLink>
    </div>

    <div v-if="loading" class="text-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="!detail" class="text-center py-12 text-gray-500">
      —
    </div>

    <div v-else>
      <header class="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div>
          <h1 class="text-2xl font-bold">{{ detail.name }}</h1>
          <p class="text-sm text-gray-500">
            {{ statusLabel(detail.status) }}
            ・{{ detail.effective_from }} 〜 {{ detail.effective_until || '—' }}
          </p>
        </div>
        <div class="flex gap-2">
          <Button
            v-if="detail.status === 'DRAFT'"
            :label="t('personalTimetable.btn_edit')"
            icon="pi pi-pencil"
            @click="$router.push(`/me/personal-timetable/${id}/edit`)"
          />
        </div>
      </header>

      <Card class="mb-4">
        <template #title>{{ t('personalTimetable.weekly_title') }}</template>
        <template #content>
          <div class="flex items-center justify-between mb-3">
            <Button
              :label="t('personalTimetable.weekly_prev')"
              icon="pi pi-chevron-left"
              size="small"
              text
              @click="shiftWeek(-7)"
            />
            <div class="text-sm font-medium">
              {{ weekly?.week_start }} 〜 {{ weekly?.week_end }}
              <span v-if="weekly?.week_pattern_enabled" class="ml-2 text-xs text-gray-500">
                ({{ weekly?.current_week_pattern }})
              </span>
            </div>
            <Button
              :label="t('personalTimetable.weekly_next')"
              icon="pi pi-chevron-right"
              icon-pos="right"
              size="small"
              text
              @click="shiftWeek(7)"
            />
          </div>
          <div class="text-center mb-2">
            <Button
              :label="t('personalTimetable.weekly_today')"
              size="small"
              outlined
              @click="goToday"
            />
          </div>

          <div v-if="weekly" class="overflow-x-auto">
            <table class="w-full border-collapse text-sm">
              <thead>
                <tr>
                  <th class="border p-2 bg-gray-50 w-20">{{ t('personalTimetable.periods_period_number') }}</th>
                  <th
                    v-for="day in DAY_KEYS"
                    :key="day"
                    class="border p-2 bg-gray-50"
                  >
                    {{ dayLabels[day] }}
                    <div class="text-xs font-normal text-gray-500">
                      {{ weekly.days[day]?.date }}
                    </div>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="period in weekly.periods.filter((p) => !p.is_break)"
                  :key="period.id"
                >
                  <th class="border p-2 bg-gray-50 text-center">
                    <div>{{ period.label }}</div>
                    <div class="text-xs font-normal text-gray-500">
                      {{ period.start_time?.slice(0, 5) }}
                    </div>
                  </th>
                  <td
                    v-for="day in DAY_KEYS"
                    :key="`${period.id}-${day}`"
                    class="border p-2 align-top min-w-[120px]"
                  >
                    <div
                      v-for="slot in (weekly.days[day]?.slots ?? []).filter((s) => s.period_number === period.period_number)"
                      :key="slot.id"
                      class="rounded p-2 mb-1"
                      :style="{ background: slot.color || '#EEF2FF' }"
                    >
                      <div class="text-sm font-semibold">{{ slot.subject_name }}</div>
                      <div v-if="slot.teacher_name" class="text-xs text-gray-700">{{ slot.teacher_name }}</div>
                      <div v-if="slot.room_name" class="text-xs text-gray-600">{{ slot.room_name }}</div>
                    </div>
                  </td>
                </tr>
                <tr v-if="weekly.periods.length === 0">
                  <td colspan="8" class="text-center p-4 text-gray-500">
                    {{ t('personalTimetable.weekly_no_slots') }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </Card>
    </div>
  </div>
</template>
