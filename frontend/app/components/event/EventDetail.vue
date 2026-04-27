<script setup lang="ts">
import { statusLabel, statusSeverity, formatDateTime } from '~/utils/eventFormat'
import AdvanceNoticeList from '~/components/event/advanceNotice/AdvanceNoticeList.vue'
import LateAbsenceNoticeBar from '~/components/event/advanceNotice/LateAbsenceNoticeBar.vue'
import DismissalDialog from '~/components/event/dismissal/DismissalDialog.vue'
import DismissalStatusBadge from '~/components/event/dismissal/DismissalStatusBadge.vue'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  eventId: number
  canEdit: boolean
}>()

const showEditDialog = ref(false)
const showDismissalDialog = ref(false)
const activeTab = ref(0)

const {
  event,
  registrations,
  checkins,
  timetableItems,
  rsvpList,
  rsvpSummary,
  myRsvpResponse,
  loading,
  loadEvent,
  loadRsvp,
  publishEvent,
  cancelEvent,
  closeRegistration,
  openRegistration,
  approveRegistration,
  rejectRegistration,
  init,
} = useEventDetail({
  scopeType: toRef(props, 'scopeType'),
  scopeId: toRef(props, 'scopeId'),
  eventId: toRef(props, 'eventId'),
})

// F03.12 Phase 10 §16 解散通知ステータス
// scopeType=team の場合のみ取得する（イベント解散通知はチーム配下のイベントのみ対応）。
const teamIdRef = computed(() => (props.scopeType === 'team' ? props.scopeId : 0))
const eventIdRef = computed(() => props.eventId)
const { status: dismissalStatus, loadStatus: loadDismissalStatus } = useDismissal(
  teamIdRef,
  eventIdRef,
)

// F03.12 Phase 10 §14/§15 タブ表示判定
const isTeamScope = computed(() => props.scopeType === 'team')
const showRollCallTab = computed(
  () => isTeamScope.value && props.canEdit && event.value?.status === 'IN_PROGRESS',
)
const showAdvanceNoticeTab = computed(() => isTeamScope.value && props.canEdit)

onMounted(async () => {
  await init()
  if (isTeamScope.value) {
    await loadDismissalStatus()
  }
})

function onDismissalSubmitted() {
  // 送信後に最新ステータスを再取得
  loadDismissalStatus()
}
</script>

<template>
  <div>
    <div v-if="loading" class="flex items-center justify-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="event">
      <div class="mb-6 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-bold">
            {{ event.subtitle || event.slug || `イベント #${event.id}` }}
          </h1>
          <div class="mt-2 flex items-center gap-3">
            <Tag :value="statusLabel(event.status)" :severity="statusSeverity(event.status)" />
            <span v-if="event.visibility === 'PUBLIC'" class="text-sm text-green-600">
              <i class="pi pi-eye mr-1" />{{ $t('event.visibility.PUBLIC') }}
            </span>
            <span v-else-if="event.visibility === 'SUPPORTERS_AND_ABOVE'" class="text-sm text-blue-500">
              <i class="pi pi-eye mr-1" />{{ $t('event.visibility.SUPPORTERS_AND_ABOVE') }}
            </span>
            <span v-else class="text-sm text-surface-500">
              <i class="pi pi-eye-slash mr-1" />{{ $t('event.visibility.MEMBERS_ONLY') }}
            </span>
            <!-- F03.12 §16 解散済みバッジ -->
            <DismissalStatusBadge v-if="dismissalStatus?.dismissed" :status="dismissalStatus" />
          </div>
        </div>
        <EventActionButtons
          v-if="canEdit"
          :status="event.status"
          :dismissal-status="dismissalStatus"
          :show-dismissal-button="isTeamScope"
          @edit="showEditDialog = true"
          @publish="publishEvent"
          @close-registration="closeRegistration"
          @open-registration="openRegistration"
          @cancel="cancelEvent"
          @dismiss="showDismissalDialog = true"
        />
      </div>

      <Card class="mb-4">
        <template #content>
          <div class="grid grid-cols-2 gap-4 md:grid-cols-3">
            <div>
              <p class="text-sm text-surface-500">会場</p>
              <p class="font-medium">{{ event.venueName || '—' }}</p>
              <p v-if="event.venueAddress" class="text-sm text-surface-500">
                {{ event.venueAddress }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">定員</p>
              <p class="font-medium">
                {{
                  event.maxCapacity
                    ? `${event.registrationCount} / ${event.maxCapacity}`
                    : `${event.registrationCount}名`
                }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">チェックイン数</p>
              <p class="font-medium">{{ event.checkinCount }}名</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付開始</p>
              <p class="font-medium">{{ formatDateTime(event.registrationStartsAt) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付終了</p>
              <p class="font-medium">{{ formatDateTime(event.registrationEndsAt) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">承認制</p>
              <p class="font-medium">{{ event.isApprovalRequired ? 'はい' : 'いいえ' }}</p>
            </div>
          </div>
          <div v-if="event.summary" class="mt-4">
            <p class="text-sm text-surface-500">概要</p>
            <p class="mt-1 whitespace-pre-wrap">{{ event.summary }}</p>
          </div>
        </template>
      </Card>

      <!-- RSVP ウィジェット -->
      <div v-if="(event.attendanceMode ?? 'NONE') === 'RSVP'" class="mb-4">
        <RsvpWidget
          :event-id="event.id"
          :scope-type="props.scopeType"
          :scope-id="props.scopeId"
          :summary="rsvpSummary"
          :my-response="myRsvpResponse"
          @responded="loadRsvp"
        />
      </div>

      <!-- F03.12 §15 事前遅刻・欠席連絡バー（チームイベントのみ） -->
      <div
        v-if="isTeamScope && (event.attendanceMode ?? 'NONE') === 'RSVP'"
        class="mb-4"
      >
        <LateAbsenceNoticeBar
          :team-id="props.scopeId"
          :event-id="event.id"
          :current-user-rsvp-status="myRsvpResponse"
        />
      </div>

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab v-if="(event.attendanceMode ?? 'NONE') === 'RSVP'" :value="0">
            {{ $t('event.rsvpList') }}
          </Tab>
          <Tab :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 1 : 0">
            {{ $t('event.participants') }}
          </Tab>
          <Tab :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 2 : 1">チェックイン</Tab>
          <Tab :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 3 : 2">タイムテーブル</Tab>
          <!-- F03.12 §14 主催者点呼タブ -->
          <Tab v-if="showRollCallTab" :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3">
            {{ $t('event.rollCall.tab') }}
          </Tab>
          <!-- F03.12 §15 事前連絡タブ -->
          <Tab
            v-if="showAdvanceNoticeTab"
            :value="
              ((event.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3) + (showRollCallTab ? 1 : 0)
            "
          >
            {{ $t('event.advanceNotice.tab') }}
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel v-if="(event.attendanceMode ?? 'NONE') === 'RSVP'" :value="0">
            <DataTable :value="rsvpList" data-key="id">
              <Column field="userName" header="氏名" />
              <Column field="response" header="回答">
                <template #body="{ data }">
                  <Tag
                    :value="$t(`event.rsvp.${data.response === 'NOT_ATTENDING' ? 'notAttending' : data.response.toLowerCase()}`)"
                    :severity="
                      data.response === 'ATTENDING'
                        ? 'success'
                        : data.response === 'NOT_ATTENDING'
                          ? 'danger'
                          : data.response === 'MAYBE'
                            ? 'warn'
                            : 'secondary'
                    "
                  />
                </template>
              </Column>
              <Column field="comment" header="コメント" />
              <Column field="respondedAt" header="回答日時">
                <template #body="{ data }">
                  {{ data.respondedAt ? new Date(data.respondedAt).toLocaleString('ja-JP') : '—' }}
                </template>
              </Column>
              <template #empty>
                <DashboardEmptyState icon="pi pi-check-circle" message="まだ回答がありません" />
              </template>
            </DataTable>
          </TabPanel>
          <TabPanel :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 1 : 0">
            <EventRegistrationTable
              :registrations="registrations"
              :can-edit="canEdit"
              @approve="approveRegistration"
              @reject="rejectRegistration"
            />
          </TabPanel>
          <TabPanel :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 2 : 1">
            <EventCheckinTable :checkins="checkins" />
          </TabPanel>
          <TabPanel :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 3 : 2">
            <EventTimetableTable :timetable-items="timetableItems" />
          </TabPanel>
          <!-- F03.12 §14 主催者点呼パネル -->
          <TabPanel
            v-if="showRollCallTab"
            :value="(event.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3"
          >
            <div
              class="flex flex-col items-start gap-3 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900"
            >
              <p class="text-sm text-surface-600 dark:text-surface-300">
                {{ $t('event.rollCall.tabDescription') }}
              </p>
              <Button
                :label="$t('event.rollCall.openSheet')"
                icon="pi pi-external-link"
                severity="primary"
                @click="navigateTo(`/teams/${props.scopeId}/events/${event.id}/roll-call`)"
              />
            </div>
          </TabPanel>
          <!-- F03.12 §15 事前連絡パネル -->
          <TabPanel
            v-if="showAdvanceNoticeTab"
            :value="
              ((event.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3) + (showRollCallTab ? 1 : 0)
            "
          >
            <AdvanceNoticeList :team-id="props.scopeId" :event-id="event.id" />
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- F03.12 §16 解散通知ダイアログ -->
      <DismissalDialog
        v-if="isTeamScope"
        v-model:open="showDismissalDialog"
        :team-id="props.scopeId"
        :event-id="event.id"
        @submitted="onDismissalSubmitted"
      />

      <EventForm
        v-model:visible="showEditDialog"
        :scope-type="props.scopeType"
        :scope-id="props.scopeId"
        :event-id="props.eventId"
        @saved="loadEvent"
      />
    </div>
  </div>
</template>
