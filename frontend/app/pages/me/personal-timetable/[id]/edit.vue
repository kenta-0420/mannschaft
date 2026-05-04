<script setup lang="ts">
import type {
  PersonalTimetable,
  PersonalTimetablePeriod,
  PersonalTimetablePeriodInput,
  PersonalTimetableShareTarget,
  PersonalTimetableSlot,
  PersonalTimetableSlotInput,
} from '~/types/personal-timetable'
import type { DayOfWeekKey, WeekPatternType } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const api = useMyPersonalTimetableApi()
const { success, error, info } = useNotification()
const teamStore = useTeamStore()

const id = computed(() => Number(route.params.id))

const detail = ref<PersonalTimetable | null>(null)
const periods = ref<PersonalTimetablePeriodInput[]>([])
const slots = ref<PersonalTimetableSlotInput[]>([])
const loading = ref(true)
const saving = ref(false)

// ----- F03.15 Phase 5b: 家族と共有セクション -----
const shareTargets = ref<PersonalTimetableShareTarget[]>([])
const shareLoading = ref(false)
const shareSubmitting = ref(false)
const selectedFamilyTeamId = ref<number | null>(null)

const SHARE_LIMIT = 3

interface FamilyTeamOption {
  value: number
  label: string
}

/** 自分の所属チームから家族チーム (template = 'family') のみを抽出。 */
const availableFamilyTeams = computed<FamilyTeamOption[]>(() => {
  const sharedIds = new Set(shareTargets.value.map(t => t.team_id))
  return teamStore.myTeams
    .filter(t => t.template === 'family')
    .filter(t => !sharedIds.has(t.id))
    .map(t => ({ value: t.id, label: t.name }))
})

const isShareLimitReached = computed(() => shareTargets.value.length >= SHARE_LIMIT)

const DAY_KEYS: DayOfWeekKey[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const WEEK_PATTERNS: WeekPatternType[] = ['EVERY', 'A', 'B']

const dayLabels = computed(() => ({
  MON: t('personalTimetable.day_mon'),
  TUE: t('personalTimetable.day_tue'),
  WED: t('personalTimetable.day_wed'),
  THU: t('personalTimetable.day_thu'),
  FRI: t('personalTimetable.day_fri'),
  SAT: t('personalTimetable.day_sat'),
  SUN: t('personalTimetable.day_sun'),
} as Record<DayOfWeekKey, string>))

const isEditable = computed(() => detail.value?.status === 'DRAFT')

async function load() {
  loading.value = true
  try {
    const [d, p, s] = await Promise.all([
      api.get(id.value),
      api.listPeriods(id.value),
      api.listSlots(id.value),
    ])
    detail.value = d
    periods.value = p.map(toPeriodInput)
    slots.value = s.map(toSlotInput)
  }
  catch (e) {
    error(t('personalTimetable.load_error'), String(e))
  }
  finally {
    loading.value = false
  }
  await loadShareTargets()
  // 家族チーム選択肢のためにユーザー所属チームを取得（store にキャッシュ）
  if (teamStore.myTeams.length === 0) {
    await teamStore.fetchMyTeams()
  }
}

async function loadShareTargets() {
  shareLoading.value = true
  try {
    shareTargets.value = await api.listShareTargets(id.value)
  }
  catch (e) {
    error(t('personalTimetable.share.load_error'), String(e))
  }
  finally {
    shareLoading.value = false
  }
}

async function addShareTarget() {
  if (selectedFamilyTeamId.value == null) return
  if (isShareLimitReached.value) {
    error(t('personalTimetable.share.limit_reached'))
    return
  }
  shareSubmitting.value = true
  const teamIdToAdd = selectedFamilyTeamId.value
  const wasPrivate = detail.value?.visibility === 'PRIVATE'
  try {
    await api.addShareTarget(id.value, { team_id: teamIdToAdd })
    success(t('personalTimetable.share.add_success'))
    selectedFamilyTeamId.value = null
    // visibility 自動切替の通知（PRIVATE → FAMILY_SHARED）
    if (wasPrivate) {
      info(t('personalTimetable.share.info_visibility_to_family_shared'))
    }
    // 共有先一覧と本体メタを再取得（visibility 反映）
    await Promise.all([loadShareTargets(), reloadDetailOnly()])
  }
  catch (e) {
    error(t('personalTimetable.share.add_error'), String(e))
  }
  finally {
    shareSubmitting.value = false
  }
}

async function removeShareTarget(teamId: number) {
  shareSubmitting.value = true
  const beforeCount = shareTargets.value.length
  try {
    await api.removeShareTarget(id.value, teamId)
    success(t('personalTimetable.share.remove_success'))
    // 全削除時の通知（FAMILY_SHARED → PRIVATE）
    if (beforeCount === 1 && detail.value?.visibility === 'FAMILY_SHARED') {
      info(t('personalTimetable.share.info_visibility_to_private'))
    }
    await Promise.all([loadShareTargets(), reloadDetailOnly()])
  }
  catch (e) {
    error(t('personalTimetable.share.remove_error'), String(e))
  }
  finally {
    shareSubmitting.value = false
  }
}

async function reloadDetailOnly() {
  try {
    detail.value = await api.get(id.value)
  }
  catch {
    // ignore — load_error は load() 側で扱う
  }
}

function toPeriodInput(p: PersonalTimetablePeriod): PersonalTimetablePeriodInput {
  return {
    period_number: p.period_number,
    label: p.label,
    start_time: p.start_time?.slice(0, 5) ?? '',
    end_time: p.end_time?.slice(0, 5) ?? '',
    is_break: p.is_break,
  }
}

function toSlotInput(s: PersonalTimetableSlot): PersonalTimetableSlotInput {
  return {
    day_of_week: s.day_of_week,
    period_number: s.period_number,
    week_pattern: s.week_pattern,
    subject_name: s.subject_name,
    course_code: s.course_code ?? null,
    teacher_name: s.teacher_name ?? null,
    room_name: s.room_name ?? null,
    credits: s.credits ?? null,
    color: s.color ?? null,
    notes: s.notes ?? null,
  }
}

function addPeriod() {
  const next = periods.value.length === 0 ? 1 : Math.max(...periods.value.map((p) => p.period_number)) + 1
  periods.value.push({
    period_number: next,
    label: `${next}限`,
    start_time: '09:00',
    end_time: '10:00',
    is_break: false,
  })
}

function removePeriod(idx: number) {
  periods.value.splice(idx, 1)
}

function addSlot() {
  slots.value.push({
    day_of_week: 'MON',
    period_number: periods.value[0]?.period_number ?? 1,
    week_pattern: 'EVERY',
    subject_name: '',
  })
}

function removeSlot(idx: number) {
  slots.value.splice(idx, 1)
}

async function save() {
  if (!isEditable.value) return
  saving.value = true
  try {
    await api.replacePeriods(id.value, periods.value)
    await api.replaceSlots(id.value, slots.value)
    success(t('personalTimetable.edit_save_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.edit_save_error'), String(e))
  }
  finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="p-4 md:p-6 max-w-6xl mx-auto">
    <div class="mb-4">
      <NuxtLink :to="`/me/personal-timetable/${id}`" class="text-sm text-blue-600 hover:underline">
        ← {{ t('personalTimetable.btn_back_to_list') }}
      </NuxtLink>
    </div>

    <div v-if="loading" class="text-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else>
      <header class="flex items-center justify-between mb-4">
        <h1 class="text-2xl font-bold">
          {{ t('personalTimetable.edit_title') }}
        </h1>
        <Button
          :label="t('personalTimetable.btn_save')"
          icon="pi pi-save"
          :loading="saving"
          :disabled="!isEditable"
          @click="save"
        />
      </header>

      <Message v-if="!isEditable" severity="warn" :closable="false" class="mb-4">
        {{ t('personalTimetable.edit_only_draft') }}
      </Message>

      <Card class="mb-6">
        <template #title>
          <div class="flex items-center justify-between">
            <span>{{ t('personalTimetable.periods_title') }}</span>
            <Button
              :label="t('personalTimetable.edit_btn_add_slot')"
              icon="pi pi-plus"
              size="small"
              outlined
              :disabled="!isEditable || periods.length >= 15"
              @click="addPeriod"
            />
          </div>
        </template>
        <template #content>
          <table class="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th class="border p-2">{{ t('personalTimetable.periods_period_number') }}</th>
                <th class="border p-2">{{ t('personalTimetable.periods_label') }}</th>
                <th class="border p-2">{{ t('personalTimetable.periods_start_time') }}</th>
                <th class="border p-2">{{ t('personalTimetable.periods_end_time') }}</th>
                <th class="border p-2">{{ t('personalTimetable.periods_is_break') }}</th>
                <th class="border p-2 w-20" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="(p, i) in periods" :key="i">
                <td class="border p-1">
                  <InputNumber v-model="p.period_number" :min="1" :max="15" :disabled="!isEditable" :show-buttons="false" />
                </td>
                <td class="border p-1">
                  <InputText v-model="p.label" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1">
                  <InputText v-model="p.start_time" type="time" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1">
                  <InputText v-model="p.end_time" type="time" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1 text-center">
                  <Checkbox v-model="p.is_break" :binary="true" :disabled="!isEditable" />
                </td>
                <td class="border p-1 text-center">
                  <Button
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    text
                    :disabled="!isEditable"
                    @click="removePeriod(i)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </Card>

      <Card class="mb-6">
        <template #title>
          {{ t('personalTimetable.share.title') }}
        </template>
        <template #content>
          <p class="text-sm text-gray-600 mb-3">
            {{ t('personalTimetable.share.description') }}
          </p>

          <div v-if="shareLoading" class="text-center py-4">
            <ProgressSpinner />
          </div>

          <div v-else>
            <!-- 共有先一覧 -->
            <div class="mb-4">
              <h3 class="text-sm font-semibold mb-2">
                {{ t('personalTimetable.share.shared_with') }}
              </h3>
              <p v-if="shareTargets.length === 0" class="text-sm text-gray-500">
                {{ t('personalTimetable.share.shared_with_empty') }}
              </p>
              <ul v-else class="space-y-2">
                <li
                  v-for="target in shareTargets"
                  :key="target.id"
                  class="flex items-center justify-between border rounded px-3 py-2 bg-gray-50"
                >
                  <span class="text-sm">
                    {{ target.team_name ?? t('personalTimetable.share.team_name_unknown') }}
                  </span>
                  <Button
                    :label="t('personalTimetable.share.btn_remove')"
                    icon="pi pi-times"
                    severity="danger"
                    size="small"
                    text
                    :loading="shareSubmitting"
                    @click="removeShareTarget(target.team_id)"
                  />
                </li>
              </ul>
            </div>

            <!-- 共有先追加 -->
            <div>
              <h3 class="text-sm font-semibold mb-2">
                {{ t('personalTimetable.share.select_family_team') }}
              </h3>
              <div v-if="availableFamilyTeams.length === 0" class="text-sm text-gray-500">
                {{ isShareLimitReached
                  ? t('personalTimetable.share.limit_reached')
                  : t('personalTimetable.share.no_family_teams') }}
              </div>
              <div v-else class="flex items-center gap-2">
                <Select
                  v-model="selectedFamilyTeamId"
                  :options="availableFamilyTeams"
                  option-value="value"
                  option-label="label"
                  :placeholder="t('personalTimetable.share.select_family_team')"
                  :disabled="shareSubmitting || isShareLimitReached"
                  class="flex-1"
                />
                <Button
                  :label="t('personalTimetable.share.btn_add')"
                  icon="pi pi-plus"
                  :disabled="selectedFamilyTeamId == null || isShareLimitReached"
                  :loading="shareSubmitting"
                  @click="addShareTarget"
                />
              </div>
            </div>
          </div>
        </template>
      </Card>

      <Card>
        <template #title>
          <div class="flex items-center justify-between">
            <span>{{ t('personalTimetable.edit_title') }}</span>
            <Button
              :label="t('personalTimetable.edit_btn_add_slot')"
              icon="pi pi-plus"
              size="small"
              outlined
              :disabled="!isEditable || slots.length >= 100"
              @click="addSlot"
            />
          </div>
        </template>
        <template #content>
          <table class="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th class="border p-2">{{ t('personalTimetable.day_mon') }}/{{ t('personalTimetable.day_sun') }}</th>
                <th class="border p-2">{{ t('personalTimetable.periods_period_number') }}</th>
                <th class="border p-2">{{ t('personalTimetable.edit_field_week_pattern') }}</th>
                <th class="border p-2">{{ t('personalTimetable.edit_field_subject') }}</th>
                <th class="border p-2">{{ t('personalTimetable.edit_field_teacher') }}</th>
                <th class="border p-2">{{ t('personalTimetable.edit_field_room') }}</th>
                <th class="border p-2 w-20" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="(s, i) in slots" :key="i">
                <td class="border p-1">
                  <Select
                    v-model="s.day_of_week"
                    :options="DAY_KEYS"
                    :disabled="!isEditable"
                    class="w-full"
                  >
                    <template #value="{ value }">{{ dayLabels[value as DayOfWeekKey] ?? value }}</template>
                    <template #option="{ option }">{{ dayLabels[option as DayOfWeekKey] }}</template>
                  </Select>
                </td>
                <td class="border p-1">
                  <Select
                    v-model="s.period_number"
                    :options="periods.filter((p) => !p.is_break).map((p) => p.period_number)"
                    :disabled="!isEditable"
                    class="w-full"
                  />
                </td>
                <td class="border p-1">
                  <Select
                    v-model="s.week_pattern"
                    :options="WEEK_PATTERNS"
                    :disabled="!isEditable || !detail?.week_pattern_enabled"
                    class="w-full"
                  />
                </td>
                <td class="border p-1">
                  <InputText v-model="s.subject_name" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1">
                  <InputText v-model="s.teacher_name" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1">
                  <InputText v-model="s.room_name" class="w-full" :disabled="!isEditable" />
                </td>
                <td class="border p-1 text-center">
                  <Button
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    text
                    :disabled="!isEditable"
                    @click="removeSlot(i)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </Card>
    </div>
  </div>
</template>
