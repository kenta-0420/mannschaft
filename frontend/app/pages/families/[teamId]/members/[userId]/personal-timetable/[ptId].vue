<script setup lang="ts">
import type { FamilyWeeklyView } from '~/types/personal-timetable'
import type { DayOfWeekKey } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const api = useFamilyPersonalTimetableApi()
const { error } = useNotification()

const teamId = computed(() => Number(route.params.teamId))
const userId = computed(() => Number(route.params.userId))
const ptId = computed(() => Number(route.params.ptId))

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

const view = ref<FamilyWeeklyView | null>(null)
const loading = ref(true)
const weekOf = ref<string | undefined>(undefined)

async function load() {
  loading.value = true
  try {
    view.value = await api.getWeekly(teamId.value, userId.value, ptId.value, weekOf.value)
  }
  catch (e) {
    error(t('personalTimetable.familyView.weekly_load_error'), String(e))
    view.value = null
  }
  finally {
    loading.value = false
  }
}

function shiftWeek(days: number) {
  const base = view.value?.week_start ?? new Date().toISOString().slice(0, 10)
  const date = new Date(base + 'T00:00:00Z')
  date.setUTCDate(date.getUTCDate() + days)
  weekOf.value = date.toISOString().slice(0, 10)
  load()
}

function jumpToToday() {
  weekOf.value = undefined
  load()
}

onMounted(load)
</script>

<template>
  <div class="p-4 md:p-6 max-w-6xl mx-auto">
    <div class="mb-4">
      <NuxtLink
        :to="`/families/${teamId}/members/${userId}/personal-timetable`"
        class="text-sm text-blue-600 hover:underline"
      >
        ← {{ t('personalTimetable.familyView.weekly_back_to_list') }}
      </NuxtLink>
    </div>

    <div v-if="loading" class="text-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="!view" class="text-center py-12 text-gray-500">
      {{ t('personalTimetable.familyView.weekly_load_error') }}
    </div>

    <div v-else>
      <header class="flex items-center justify-between mb-4">
        <div>
          <h1 class="text-2xl font-bold">
            {{ view.personal_timetable_name }}
          </h1>
          <p class="text-xs text-gray-500 mt-1">
            {{ view.week_start }} 〜 {{ view.week_end }}
            <span v-if="view.week_pattern_enabled">
              / {{ view.current_week_pattern }}
            </span>
          </p>
        </div>
        <div class="flex items-center gap-2">
          <Button
            :label="t('personalTimetable.weekly_prev')"
            icon="pi pi-chevron-left"
            size="small"
            outlined
            @click="shiftWeek(-7)"
          />
          <Button
            :label="t('personalTimetable.weekly_today')"
            size="small"
            outlined
            @click="jumpToToday"
          />
          <Button
            :label="t('personalTimetable.weekly_next')"
            icon="pi pi-chevron-right"
            icon-pos="right"
            size="small"
            outlined
            @click="shiftWeek(7)"
          />
        </div>
      </header>

      <Message severity="info" :closable="false" class="mb-4">
        {{ t('personalTimetable.familyView.private_notice') }}
      </Message>

      <table class="w-full border-collapse text-sm bg-white shadow rounded">
        <thead class="bg-gray-50">
          <tr>
            <th class="border p-2 w-32">
              {{ t('personalTimetable.periods_period_number') }}
            </th>
            <th
              v-for="day in DAY_KEYS"
              :key="day"
              class="border p-2"
            >
              {{ dayLabels[day] }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="period in view.periods" :key="period.id">
            <td class="border p-2 align-top">
              <div class="font-semibold">{{ period.label }}</div>
              <div class="text-xs text-gray-500">
                {{ period.start_time?.slice(0, 5) }} – {{ period.end_time?.slice(0, 5) }}
              </div>
            </td>
            <td
              v-for="day in DAY_KEYS"
              :key="day"
              class="border p-2 align-top"
            >
              <div
                v-for="slot in (view.days[day]?.slots ?? []).filter(s => s.period_number === period.period_number)"
                :key="slot.id"
                class="rounded p-2 mb-1"
                :style="{ backgroundColor: slot.color ?? '#EEF6FF' }"
              >
                <div class="font-semibold text-xs">{{ slot.subject_name }}</div>
                <div v-if="slot.teacher_name" class="text-xs text-gray-700">
                  {{ slot.teacher_name }}
                </div>
                <div v-if="slot.room_name" class="text-xs text-gray-600">
                  {{ slot.room_name }}
                </div>
                <div v-if="slot.course_code" class="text-xs text-gray-500">
                  {{ slot.course_code }}
                </div>
                <div v-if="slot.credits != null" class="text-xs text-gray-500">
                  {{ slot.credits }} 単位
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
