<script setup lang="ts">
import { statusLabel, statusSeverity, formatDateTime } from '~/utils/eventFormat'
import AdvanceNoticeList from '~/components/event/advanceNotice/AdvanceNoticeList.vue'
import LateAbsenceNoticeBar from '~/components/event/advanceNotice/LateAbsenceNoticeBar.vue'
import DismissalDialog from '~/components/event/dismissal/DismissalDialog.vue'
import DismissalStatusBadge from '~/components/event/dismissal/DismissalStatusBadge.vue'
import EventDelegationAdminPanel from '~/components/event/EventDelegationAdminPanel.vue'
import type { EventChatChannelResponse } from '~/types/event'

const { formatDateTime: formatIsoDateTime } = useDatetime()

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  eventId: number
  canEdit: boolean
}>()

const showEditDialog = ref(false)
const showDismissalDialog = ref(false)
const activeTab = ref(0)

// チャットチャンネル情報
const chatChannel = ref<EventChatChannelResponse | null>(null)
const { getEventChannel } = useEventChatChannel()

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
const teamIdRef = computed(() => (props.scopeType === 'team' ? props.scopeId : ''))
const eventIdRef = computed(() => props.eventId)
const { status: dismissalStatus, loadStatus: loadDismissalStatus } = useDismissal(
  teamIdRef,
  eventIdRef,
)

// F03.12 Phase 10 §14/§15 タブ表示判定
const isTeamScope = computed(() => props.scopeType === 'team')
const showRollCallTab = computed(
  () => isTeamScope.value && props.canEdit && event.value?.meta?.status === 'IN_PROGRESS',
)
const showAdvanceNoticeTab = computed(() => isTeamScope.value && props.canEdit)

// F03.10 第四陣 Wave2-B §管理者向け代理出席タブ（管理者のみ表示）
const showDelegationTab = computed(() => props.canEdit)

/** RSVP あり/なし基底オフセット（0 または 1） */
const rsvpOffset = computed(() =>
  (event.value?.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 1 : 0,
)

/**
 * 代理出席タブの value。
 * 固定タブ（RSVP/参加者/チェックイン/タイムテーブル）と
 * 点呼タブ・事前連絡タブの後に置く。
 */
const delegationTabValue = computed(() => {
  // 基底: RSVP(+1) + 参加者/チェックイン/タイムテーブル(+3) = 基底+3
  let val = rsvpOffset.value + 3
  if (showRollCallTab.value) val++
  if (showAdvanceNoticeTab.value) val++
  return val
})

/**
 * チャットタブの value。
 * 固定タブ（参加者/チェックイン/タイムテーブル）の後に
 * 点呼タブ・事前連絡タブ・代理出席タブが続き、その後にチャットタブを置く。
 */
const chatTabValue = computed(() => {
  let val = delegationTabValue.value
  if (showDelegationTab.value) val++
  return val
})

onMounted(async () => {
  await init()
  if (isTeamScope.value) {
    await loadDismissalStatus()
  }
  // チャットチャンネル情報を取得（404 の場合は null のまま = 表示しない）
  chatChannel.value = await getEventChannel(props.eventId)
})

function onDismissalSubmitted() {
  // 送信後に最新ステータスを再取得
  loadDismissalStatus()
}
</script>

<template>
  <div>
    <div v-if="loading" class="flex items-center justify-center py-12">
      <LoadingBounce />
    </div>

    <div v-else-if="event">
      <div class="mb-6 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-bold">
            {{ event.content?.subtitle || event.content?.slug || `イベント #${event.id}` }}
          </h1>
          <div class="mt-2 flex items-center gap-3">
            <Tag :value="statusLabel(event.meta?.status ?? '')" :severity="statusSeverity(event.meta?.status ?? '')" />
            <span v-if="event.meta?.visibility === 'PUBLIC'" class="text-sm text-green-600">
              <i class="pi pi-eye mr-1" />{{ $t('event.visibility.PUBLIC') }}
            </span>
            <span v-else-if="event.meta?.visibility === 'SUPPORTERS_AND_ABOVE'" class="text-sm text-blue-500">
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
          :status="event.meta?.status ?? ''"
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
              <p class="font-medium">{{ event.venue?.venueName || '—' }}</p>
              <p v-if="event.venue?.venueAddress" class="text-sm text-surface-500">
                {{ event.venue?.venueAddress }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">定員</p>
              <p class="font-medium">
                {{
                  event.registration?.maxCapacity
                    ? `${event.registration?.registrationCount} / ${event.registration?.maxCapacity}`
                    : `${event.registration?.registrationCount}名`
                }}
              </p>
            </div>
            <div>
              <p class="text-sm text-surface-500">チェックイン数</p>
              <p class="font-medium">{{ event.registration?.checkinCount }}名</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付開始</p>
              <p class="font-medium">{{ formatDateTime(event.registration?.registrationStartsAt ?? null) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">受付終了</p>
              <p class="font-medium">{{ formatDateTime(event.registration?.registrationEndsAt ?? null) }}</p>
            </div>
            <div>
              <p class="text-sm text-surface-500">承認制</p>
              <p class="font-medium">{{ event.registration?.isApprovalRequired ? 'はい' : 'いいえ' }}</p>
            </div>
          </div>
          <div v-if="event.content?.summary" class="mt-4">
            <p class="text-sm text-surface-500">概要</p>
            <p class="mt-1 whitespace-pre-wrap">{{ event.content?.summary }}</p>
          </div>
        </template>
      </Card>

      <!-- RSVP ウィジェット -->
      <div v-if="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP'" class="mb-4">
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
        v-if="isTeamScope && (event.registration?.attendanceMode ?? 'NONE') === 'RSVP'"
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
          <Tab v-if="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP'" :value="0">
            {{ $t('event.rsvpList') }}
          </Tab>
          <Tab :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 1 : 0">
            {{ $t('event.participants') }}
          </Tab>
          <Tab :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 2 : 1">チェックイン</Tab>
          <Tab :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 3 : 2">タイムテーブル</Tab>
          <!-- F03.12 §14 主催者点呼タブ -->
          <Tab v-if="showRollCallTab" :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3">
            {{ $t('event.rollCall.tab') }}
          </Tab>
          <!-- F03.12 §15 事前連絡タブ -->
          <Tab
            v-if="showAdvanceNoticeTab"
            :value="
              ((event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3) + (showRollCallTab ? 1 : 0)
            "
          >
            {{ $t('event.advanceNotice.tab') }}
          </Tab>
          <!-- F03.10 第四陣 Wave2-B 代理出席タブ（管理者のみ表示） -->
          <Tab v-if="showDelegationTab" :value="delegationTabValue">
            {{ $t('proxy.delegation.admin.tab') }}
          </Tab>
          <!-- チャットタブ（チャンネルが存在する場合のみ表示） -->
          <Tab v-if="chatChannel" :value="chatTabValue">
            {{ $t('event.chatOpen') }}
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel v-if="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP'" :value="0">
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
                  {{ data.respondedAt ? formatIsoDateTime(data.respondedAt) : '—' }}
                </template>
              </Column>
              <template #empty>
                <DashboardEmptyState icon="pi pi-check-circle" message="まだ回答がありません" />
              </template>
            </DataTable>
          </TabPanel>
          <TabPanel :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 1 : 0">
            <EventRegistrationTable
              :registrations="registrations"
              :can-edit="canEdit"
              @approve="approveRegistration"
              @reject="rejectRegistration"
            />
          </TabPanel>
          <TabPanel :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 2 : 1">
            <EventCheckinTable :checkins="checkins" />
          </TabPanel>
          <TabPanel :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 3 : 2">
            <EventTimetableTable :timetable-items="timetableItems" />
          </TabPanel>
          <!-- F03.12 §14 主催者点呼パネル -->
          <TabPanel
            v-if="showRollCallTab"
            :value="(event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3"
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
              ((event.registration?.attendanceMode ?? 'NONE') === 'RSVP' ? 4 : 3) + (showRollCallTab ? 1 : 0)
            "
          >
            <AdvanceNoticeList :team-id="props.scopeId" :event-id="event.id" />
          </TabPanel>
          <!-- F03.10 第四陣 Wave2-B 代理出席パネル（管理者のみ表示） -->
          <TabPanel v-if="showDelegationTab" :value="delegationTabValue">
            <EventDelegationAdminPanel
              :scope-type="props.scopeType"
              :scope-id="props.scopeId"
              :event-id="props.eventId"
            />
          </TabPanel>
          <!-- チャットパネル（チャンネルが存在する場合のみ表示） -->
          <TabPanel v-if="chatChannel" :value="chatTabValue">
            <div
              class="flex flex-col items-start gap-3 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900"
            >
              <NuxtLink
                :to="`/chat?channel=${chatChannel.id}`"
                class="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-contrast hover:bg-primary-emphasis"
              >
                <i class="pi pi-comments" />
                {{ $t('event.chatOpen') }}
              </NuxtLink>
            </div>
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
