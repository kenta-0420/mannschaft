<script setup lang="ts">
import dayjs from 'dayjs'
import type { ShiftScheduleResponse } from '~/types/shift'
definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const shiftApi = useShiftApi()

// 数値 teamId が必要な API 向けの解決経路。
// `TeamResponse.id` は slug と同値の URL 識別子であり数値 ID ではないため、必ず `numericId` を使う。
//
// 親 pages/teams/[slug].vue の provide は「シェル対象ルート」でしか team を取得しない
// （SHELL_SEGMENTS に 'shifts' は含まれない）。したがって本ページでは team が永久に null であり、
// シェルの provide だけに頼ると ShiftSwapList が骨組みのまま一生描画されない。
// 未解決を握りつぶさず、本ページ自身で slug → numericId を解決する。
const { team } = useTeamShellContext()
const notification = useNotification()
const { t } = useI18n()
const teamApi = useTeamApi()
const resolvedNumericId = ref<number | null>(null)
const teamNumericId = computed<number | null>(
  () => team.value?.numericId ?? resolvedNumericId.value,
)

async function resolveTeamNumericId() {
  if (teamNumericId.value !== null) return
  try {
    const res = await teamApi.getTeam(teamSlug)
    resolvedNumericId.value = res.data.numericId ?? null
  } catch {
    notification.error(t('shift.page.teamLoadFailed'))
  }
}
const { isAdmin, isAdminOrDeputy, roleName, loadPermissions } = useRoleAccess('team', teamSlug)
// シフトボードは当該チームの ADMIN / DEPUTY_ADMIN 限定。
// 判定手段は board.vue（`shifts/[scheduleId]/board.vue` の isScopeAdmin）に合わせる。
const isScopeAdmin = computed(
  () => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN',
)
const { userTimezone } = useDatetime()

const activeTab = ref(0)
const showCreateDialog = ref(false)
const showRequestDialog = ref(false)
const selectedScheduleId = ref<number | null>(null)

// シフト表作成フォーム
const createForm = ref({
  title: '',
  periodStart: null as Date | null,
  periodEnd: null as Date | null,
})
const creating = ref(false)

async function createSchedule() {
  if (
    !createForm.value.title.trim() ||
    !createForm.value.periodStart ||
    !createForm.value.periodEnd
  )
    return
  creating.value = true
  try {
    await shiftApi.createSchedule(teamSlug, {
      title: createForm.value.title.trim(),
      startDate: dayjs(createForm.value.periodStart).tz(userTimezone.value).format('YYYY-MM-DD'),
      endDate: dayjs(createForm.value.periodEnd).tz(userTimezone.value).format('YYYY-MM-DD'),
    })
    notification.success('シフト表を作成しました')
    showCreateDialog.value = false
    await loadSchedules()
    createForm.value = { title: '', periodStart: null, periodEnd: null }
  } catch {
    notification.error('作成に失敗しました')
  } finally {
    creating.value = false
  }
}

// --- 希望受付の可否判定（CMP-260908-2118）--------------------------------
// BE は ShiftRequestService#validateCollectingStatus / #validateRequestDeadline の
// 2 つのガードを持つ。画面がこれを見ていないため「押しても必ず失敗するボタン」が
// 出ていた。一覧レスポンス（ShiftScheduleResponse）は status.status と
// period.requestDeadline を持つので、それだけで受付可否を判定できる。
const schedules = ref<ShiftScheduleResponse[]>([])

async function loadSchedules() {
  try {
    schedules.value = await shiftApi.listSchedules(teamSlug)
  } catch {
    notification.error(t('shift.notification.errorLoad'))
    schedules.value = []
  }
}

function isAcceptingRequests(s: ShiftScheduleResponse): boolean {
  if (s.status.status !== 'COLLECTING') return false
  const deadline = s.period.requestDeadline
  if (!deadline) return true
  return dayjs().isBefore(dayjs(deadline))
}

const acceptingScheduleIds = computed(() =>
  schedules.value.filter(isAcceptingRequests).map((s) => s.id),
)

function onScheduleSelect(id: number) {
  // 受付終了のシフト表は希望ダイアログも開かせない（開いても BE に必ず弾かれるため）
  if (schedules.value.length > 0 && !acceptingScheduleIds.value.includes(id)) {
    notification.warn(t('shift.entry.closed'))
    return
  }
  selectedScheduleId.value = id
  showRequestDialog.value = true
}

onMounted(() => {
  loadPermissions()
  void resolveTeamNumericId()
  void loadSchedules()
})
</script>

<template>
  <div>
    <!-- ネストされたページ（ボード・変更依頼）が存在する場合はそちらに委譲 -->
    <NuxtPage v-if="$route.params.scheduleId" />
    <template v-else>
    <PageHeader title="シフト管理" class="mb-4" />

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">シフト表</Tab>
        <Tab :value="1">シフト交換</Tab>
        <Tab :value="2">ポジション管理</Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <ShiftScheduleList
            :team-id="teamSlug"
            :can-manage="isAdminOrDeputy"
            @select="onScheduleSelect"
            @create="showCreateDialog = true"
          />
          <!-- 一括希望入力・シフトボードへの導線（CMP-260908-2118） -->
          <ShiftScheduleRequestActions
            :team-slug="teamSlug"
            :schedules="schedules"
            :accepting-schedule-ids="acceptingScheduleIds"
            :can-manage="isScopeAdmin"
          />
          <!-- 曜日ごとの既定希望ページへの導線（FE 全体でリンクが無く到達不能だった） -->
          <div class="mt-4">
            <Button
              :label="t('shift.entry.weeklyDefault')"
              icon="pi pi-calendar-clock"
              text
              size="small"
              @click="navigateTo('/my/shift-availability')"
            />
          </div>
        </TabPanel>
        <TabPanel :value="1">
          <!-- 交代申請APIは数値 teamId を要求する。解決前は骨組みを出して誤リクエストを撃たない -->
          <ShiftSwapList
            v-if="teamNumericId !== null"
            :team-id="teamNumericId"
            :can-manage="isAdminOrDeputy"
          />
          <div v-else>
            <Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" />
          </div>
        </TabPanel>
        <TabPanel v-if="isAdmin" :value="2">
          <ShiftPositionManager :team-id="teamSlug" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- シフト表作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="シフト表を作成"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル</label>
          <InputText v-model="createForm.title" class="w-full" placeholder="例: 2026年4月第1週" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日</label>
            <DatePicker
              v-model="createForm.periodStart"
              date-format="yy/mm/dd"
              class="w-full"
              show-icon
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日</label>
            <DatePicker
              v-model="createForm.periodEnd"
              date-format="yy/mm/dd"
              class="w-full"
              show-icon
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" :loading="creating" @click="createSchedule" />
      </template>
    </Dialog>

    <!-- シフト希望提出ダイアログ -->
    <ShiftRequestForm
      v-if="selectedScheduleId"
      v-model:visible="showRequestDialog"
      :team-id="teamSlug"
      :schedule-id="selectedScheduleId"
    />
    </template>
  </div>
</template>
