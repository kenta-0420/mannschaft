<script setup lang="ts">
import type { ReservationSettingsResponse, ReservationLineResponse } from '~/types/reservation'
import type { components } from '~/types/generated'

type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']
type SlotTemplateResponse = components['schemas']['SlotTemplateResponse']
type RecurringBlockedTimeResponse = components['schemas']['RecurringBlockedTimeResponse']

const props = defineProps<{ teamId: string }>()

const { t } = useI18n()
const { isAdmin, isAdminOrDeputy, isMember, roleName, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

/** 呼称の動的差し込み（F03.4.5 §5.2）: 管理タブ名・件数バッジに使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

const activeTab = ref(0)
/** 使い方ガイドモーダルの表示状態。 */
const showGuide = ref(false)

// 予約タブの表示はマトリックス（機能H SlotMatrixPicker）に一本化した。
// 旧リスト表示（SlotPicker）・旧staff軸グリッド（SlotGridPicker）と表示切替 UI・
// localStorage の表示選好は撤去済み（マスター裁可 2026-08-04）。
const showBookDialog = ref(false)
const selectedSlot = ref({ slotId: 0, lineId: 0, lineName: '', date: '', startTime: '', endTime: '' })

/** 予約直後の再読込用 ref。既存 MatchRequestList 等と同一パターン（defineExpose({ refresh })）。 */
type Refreshable = { refresh: () => void | Promise<void> }
const slotMatrixPickerRef = ref<Refreshable | null>(null)
const reservationListRef = ref<Refreshable | null>(null)
/** 自分のキャンセル待ち一覧（W2-4-FE）。予約成立時に WAITING→CONVERTED で消え得るため onReserved から再読込する。 */
const myWaitlistListRef = ref<Refreshable | null>(null)

/** 詳細設定アコーディオンの開閉状態（初期 collapsed） */
const advancedSettingsValue = ref<string | null>(null)

/**
 * 「予約対象の管理」タブ内セクション（ライン/メニュー/週間テンプレート/枠管理）のアコーディオン開閉状態。
 * 初期は全閉（ADHD配慮=脳内摩擦削減。F03.4.5 §UX改善5点の2)）。multiple=true のため配列で複数開閉を保持する。
 */
const managementAccordionValue = ref<string[]>([])

/** アコーディオン件数バッジ用の子コンポーネント参照（既存 FriendFolderList/friend-folders.vue と同一パターン）。 */
const lineManagerRef = ref<{ refresh: () => Promise<void>; items: ReservationLineResponse[] } | null>(null)
const menuManagerRef = ref<{ refresh: () => Promise<void>; items: ReservationMenuResponse[] } | null>(null)
// 検分指摘（軽4）: 週間スケジュールのバッジは「枠テンプレ＋定期予約不可」の合算件数を表すため、
// recurringItems（WeeklyScheduleManager 側で追加 expose・§4.5）も型に含める。
const slotTemplateManagerRef = ref<{
  refresh: () => Promise<void>
  items: SlotTemplateResponse[]
  recurringItems: RecurringBlockedTimeResponse[]
} | null>(null)
const businessHoursManagerRef = ref<{ refresh: () => Promise<void> } | null>(null)

const lineCount = computed(() => lineManagerRef.value?.items?.length ?? 0)
const menuCount = computed(() => menuManagerRef.value?.items?.length ?? 0)
/**
 * 週間スケジュールセクションの件数バッジ（検分指摘・軽4）。
 * 「枠テンプレのみ」を数えていたため、定期予約不可枠だけ登録したチームで実データがあるのに
 * バッジが (0) と表示され利用者を欺いていた。枠テンプレ＋定期予約不可ルールの合算に修正する。
 */
const weeklyScheduleCount = computed(() =>
  (slotTemplateManagerRef.value?.items?.length ?? 0)
  + (slotTemplateManagerRef.value?.recurringItems?.length ?? 0),
)

/**
 * ④週間スケジュールの空状態から「①営業時間を設定する」導線が押されたとき、
 * 営業時間セクションを開いてスクロールする（F03.4.5 §3.2・S-11）。
 */
function openBusinessHoursSection() {
  if (!managementAccordionValue.value.includes('business_hours')) {
    managementAccordionValue.value = [...managementAccordionValue.value, 'business_hours']
  }
  if (import.meta.client) {
    nextTick(() => {
      document.getElementById('reservation-business-hours-section')
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
}

/** 営業時間の保存成功時に予約設定キャッシュ（hasBusinessHours）を最新化する。 */
function onBusinessHoursSaved() {
  void loadReservationSettings()
}

// === 予約設定（PUBLIC許可フラグ） ===
const reservationSettings = ref<ReservationSettingsResponse | null>(null)
const settingsLoading = ref(false)

/** 所属者（SUPPORTER含む）判定。BE の ReservationService 認可ゲート（memberships 経由の isMember）と定義を揃える */
const isAffiliated = computed<boolean>(() => isMember.value || roleName.value === 'SUPPORTER')

/** 非所属ユーザーに予約導線を表示するか。設定OFFかつ非所属の場合は案内文に切り替え */
const canBook = computed<boolean>(() => {
  // 所属者（ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER）は常に予約可
  if (isAffiliated.value) return true
  // 非所属（GUEST/未ログイン）: allowPublicReservation が true の場合のみ可
  return reservationSettings.value?.allowPublicReservation === true
})

async function loadReservationSettings() {
  settingsLoading.value = true
  try {
    const { getReservationSettings } = useReservationApi()
    const res = await getReservationSettings(props.teamId)
    reservationSettings.value = res.data
  }
  catch {
    // 取得失敗は安全方向（allowPublicReservation=false）に fallback
    reservationSettings.value = null
  }
  finally {
    settingsLoading.value = false
  }
}

/**
 * 予約確定直後（ReservationForm の @reserved）に、表示中の枠（空き状況）と予約一覧を再読込する。
 * 再読込を怠ると「予約確定→ダイアログを閉じても枠が空きありのまま／一覧に反映されない」というズレが
 * 再読込（タブ切替やページ再訪）まで残ってしまうため、ここで能動的に再読込する（根治治療）。
 */
function onReserved() {
  slotMatrixPickerRef.value?.refresh()
  reservationListRef.value?.refresh()
  // 予約成立で自分の WAITING が CONVERTED に消し込まれている可能性があるため再読込する（W2-4-FE）。
  myWaitlistListRef.value?.refresh()
}

/**
 * 予約一覧タブでの操作（承認/却下/キャンセル、ReservationList の @changed）直後に、
 * 予約するタブ（マトリックス）の空き表示を再読込する（#2179「予約→一覧」の逆方向）。
 * 一覧タブが非アクティブでも Picker はマウント済み（TabPanels 非 lazy）のため refresh は常に有効。
 * ReservationList 自身は既に loadReservations() 済みのため reservationListRef は対象外。
 */
function onSlotsChanged() {
  slotMatrixPickerRef.value?.refresh()
}

/**
 * SlotMatrixPicker のキャンセル待ちダイアログで登録/取消が成功した（W2-4-FE）。
 * 「自分のキャンセル待ち」一覧（予約一覧タブ）を再読込する。
 */
function onWaitlistChanged() {
  myWaitlistListRef.value?.refresh()
}

function onSlotSelected(
  slotId: number,
  lineId: number,
  lineName: string,
  date: string,
  startTime: string,
  endTime: string,
) {
  selectedSlot.value = { slotId, lineId, lineName, date, startTime, endTime }
  showBookDialog.value = true
}

/** ReservationPublicToggle から emit される変更を受け取りキャッシュを更新する */
function onPublicToggleChanged(value: boolean) {
  if (reservationSettings.value) {
    reservationSettings.value = { ...reservationSettings.value, allowPublicReservation: value }
  }
  else {
    reservationSettings.value = { allowPublicReservation: value }
  }
}

/** ReservationPolicySettings から emit される変更を受け取りキャッシュを更新する */
function onPolicyChanged(
  approvalMode: 'AUTO' | 'MANUAL',
  cancelDeadlineHours: number,
  remindBeforeHours: string,
) {
  reservationSettings.value = {
    ...(reservationSettings.value ?? {}),
    approvalMode,
    cancelDeadlineHours,
    remindBeforeHours,
  }
}

/**
 * 呼称設定（ReservationResourceNameSettings）の保存成功時: 予約設定キャッシュを最新化し、
 * 同一テーブル上で独立フェッチしている他コンポーネント（LineManager・SlotMatrixPicker）の
 * 呼称表示も再読込する（各コンポーネントの useResourceName は独立フェッチのため
 * 明示的に refresh を連鎖させないと画面上の呼称表示が古いまま残る）。
 */
function onResourceNameChanged() {
  void loadReservationSettings()
  void loadResourceName()
  void lineManagerRef.value?.refresh()
  void slotMatrixPickerRef.value?.refresh()
}

onMounted(async () => {
  await loadPermissions()
  await loadReservationSettings()
  await loadResourceName()
})
</script>

<template>
  <div>
    <!-- ツールバー: 使い方ガイド入口（常時表示）-->
    <div class="mb-3 flex items-center justify-end">
      <Button
        icon="pi pi-question-circle"
        :label="t('reservation.team_guide.help_button')"
        text
        size="small"
        @click="showGuide = true"
      />
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">{{ t('reservation.tab.book') }}</Tab>
        <!-- 非管理者（SUPPORTER含む）は「自分の予約」に改名（表示のみ・認可挙動は不変）。
             管理者/副管理者（mode=team）は従来通り「予約一覧」。 -->
        <Tab :value="1">{{ isAdminOrDeputy ? t('reservation.tab.list') : t('reservation.tab.my_reservations') }}</Tab>
        <!-- ②予約対象タブ: ADMIN は全機能、DEPUTY_ADMIN は呼称のみ（マスター裁可 2026-07-11）。タブ自体は両者に開放 -->
        <Tab v-if="isAdminOrDeputy" :value="2">{{ t('reservation.tab.line_manage', { resourceName }) }}</Tab>
        <Tab v-if="isAdminOrDeputy" :value="3">{{ t('reservation.tab.emergency_closure') }}</Tab>
      </TabList>
      <TabPanels>
        <!-- 予約タブ: 非所属かつ設定OFFの場合は案内文を表示 -->
        <TabPanel :value="0">
          <div v-if="settingsLoading" class="py-8 text-center">
            <Skeleton height="4rem" width="100%" />
          </div>
          <template v-else-if="canBook">
            <!-- イベント募集への導線（結線なし・NuxtLinkのみ。F03.4.5 §UX第三弾第一波） -->
            <div class="mb-3 flex justify-end">
              <NuxtLink
                :to="`/teams/${props.teamId}/events`"
                class="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                data-testid="reservation-event-link"
              >
                <i class="pi pi-calendar" aria-hidden="true" />
                {{ t('reservation.team_guide.event_link') }}
              </NuxtLink>
            </div>
            <!-- 予約枠の表示はマトリックス一本（旧リスト/旧staff軸グリッドは撤去済み）。
                 TabPanels は非 lazy のためタブ切替でも破棄されず、KeepAlive は不要。 -->
            <SlotMatrixPicker
              ref="slotMatrixPickerRef"
              :team-id="props.teamId"
              :is-admin="isAdmin"
              @slot-selected="onSlotSelected"
              @manage-lines="activeTab = 2"
              @reserved="onReserved"
              @waitlist-changed="onWaitlistChanged"
            />
          </template>
          <div v-else class="rounded-lg border border-surface-200 p-6 text-center dark:border-surface-700">
            <i class="pi pi-lock mb-3 block text-3xl text-surface-400" />
            <p class="text-sm font-medium text-surface-700 dark:text-surface-300">
              {{ t('reservation.book.not_affiliated_notice') }}
            </p>
            <p class="mt-1 text-xs text-surface-500">
              {{ t('reservation.book.not_affiliated_hint') }}
            </p>
          </div>
        </TabPanel>

        <!-- 予約一覧タブ: 管理者はチーム全件、非管理者は自分の予約のみ（他人の氏名・予約は非表示）-->
        <TabPanel :value="1">
          <!-- 自分のキャンセル待ち（F03.4.5 §6.1 W2-4-FE）。既存タブ内に埋め込み・新規ページは作らない -->
          <div class="mb-6">
            <ReservationMyWaitlistList ref="myWaitlistListRef" :team-id="props.teamId" />
          </div>
          <ReservationList
            v-if="isAdminOrDeputy"
            ref="reservationListRef"
            :team-id="props.teamId"
            :can-manage="true"
            mode="team"
            @changed="onSlotsChanged"
          />
          <ReservationList
            v-else
            ref="reservationListRef"
            :team-id="props.teamId"
            :can-manage="false"
            mode="mine"
            @changed="onSlotsChanged"
          />
        </TabPanel>

        <!-- ②予約対象タブ: ADMIN=全管理機能 / DEPUTY_ADMIN=呼称設定のみ（マスター裁可 2026-07-11）。
             タブ自体は isAdminOrDeputy に開放し、中身をロールで出し分ける（ライン/メニュー管理は ADMIN 限定を維持）。 -->
        <TabPanel v-if="isAdminOrDeputy" :value="2">
          <!-- ADMIN: ライン管理 + メニュー管理 + 週間テンプレート + 枠管理 + 詳細設定アコーディオン（従来どおり） -->
          <template v-if="isAdmin">
          <!-- 管理セクション群（初期は全閉・ADHD配慮で脳内摩擦削減。件数バッジ付き）
               並び順は初期セットアップの思考順（F03.4.5 §3.2 確定）:
               ①営業時間 → ②予約対象 → ③メニュー → ④週間スケジュール → ⑤例外日カレンダー → ⑥詳細設定（Accordion外・下部）。 -->
          <!-- 注意: AccordionContent に lazy を付けると閉状態の子が非マウントになり、件数バッジ（子の defineExpose({ items }) 参照）が機能しなくなる。常時マウント前提のため lazy 禁止 -->
          <Accordion v-model:value="managementAccordionValue" multiple>
            <!-- ①営業時間（BusinessHoursManager・F03.4.5 §3.2 新設） -->
            <AccordionPanel id="reservation-business-hours-section" value="business_hours">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.business_hours.title') }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <ReservationBusinessHoursManager
                  ref="businessHoursManagerRef"
                  :team-id="props.teamId"
                  :disabled="!isAdmin"
                  @saved="onBusinessHoursSaved"
                />
              </AccordionContent>
            </AccordionPanel>

            <!-- ②予約対象（呼称設定＋LineManager。呼称設定は F03.4.5 §5.1: セクション先頭に配置） -->
            <AccordionPanel value="lines">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.management.section_count', { label: t('reservation.line_manage_title', { resourceName }), n: lineCount }) }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <div class="mb-4">
                  <!-- 呼称設定は ADMIN・DEPUTY_ADMIN とも編集可（マスター裁可 2026-07-11・設計§2）。ライン管理は ADMIN のみ -->
                  <ReservationResourceNameSettings
                    :team-id="props.teamId"
                    :disabled="!isAdminOrDeputy"
                    @changed="onResourceNameChanged"
                  />
                </div>
                <LineManager ref="lineManagerRef" :team-id="props.teamId" />
              </AccordionContent>
            </AccordionPanel>

            <!-- ③メニュー管理セクション（機能E・F03.4.1） -->
            <AccordionPanel value="menus">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.management.section_count', { label: t('reservation.menu.title'), n: menuCount }) }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <MenuManager ref="menuManagerRef" :team-id="props.teamId" />
              </AccordionContent>
            </AccordionPanel>

            <!-- ④週間スケジュール管理セクション（旧 SlotTemplateManager・F03.4.2/F03.4.5 §3.2）。
                 ラベルは「枠テンプレ」限定表現ではなく、枠テンプレ＋定期予約不可を包含する
                 「週間スケジュール」（検分指摘・軽4）。 -->
            <AccordionPanel value="weekly_schedule">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.management.section_count', { label: t('reservation.weekly_schedule.section_title'), n: weeklyScheduleCount }) }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <WeeklyScheduleManager
                  ref="slotTemplateManagerRef"
                  :team-id="props.teamId"
                  :has-business-hours="reservationSettings?.hasBusinessHours ?? true"
                  @focus-business-hours="openBusinessHoursSection"
                />
              </AccordionContent>
            </AccordionPanel>

            <!-- ⑤例外日カレンダー（F03.4.5 §3.3・W2-1第二隊で実装完了） -->
            <AccordionPanel value="exception_day">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.exception_day.title') }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <ScheduleExceptionPanel
                  :team-id="props.teamId"
                  @goto-list="activeTab = 1"
                  @goto-emergency-closure="activeTab = 3"
                  @goto-book="activeTab = 0"
                />
              </AccordionContent>
            </AccordionPanel>
          </Accordion>

          <!-- 詳細設定（ADMIN限定・既定 collapsed）-->
          <div class="mt-6">
            <Accordion v-model:value="advancedSettingsValue">
              <AccordionPanel value="advanced">
                <AccordionHeader>
                  <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                    {{ t('reservation.settings.advanced.title') }}
                  </span>
                </AccordionHeader>
                <AccordionContent>
                  <div class="space-y-4 p-2">
                    <!-- PUBLIC予約許可トグル -->
                    <div>
                      <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-surface-500">
                        {{ t('reservation.settings.allow_public.section_label') }}
                      </p>
                      <ReservationPublicToggle
                        :team-id="props.teamId"
                        :disabled="!isAdmin"
                        @changed="onPublicToggleChanged"
                      />
                    </div>

                    <Divider />

                    <!-- 予約ポリシー設定（承認モード・キャンセル期限・リマインド）-->
                    <div>
                      <p class="mb-3 text-xs font-semibold uppercase tracking-wide text-surface-500">
                        {{ t('reservation.settings.policy.section_label') }}
                      </p>
                      <ReservationPolicySettings
                        :team-id="props.teamId"
                        :disabled="!isAdmin"
                        @changed="onPolicyChanged"
                      />
                    </div>

                    <Divider />

                    <!-- 予約不可枠（機能B）: 対象別・時間帯単位の予約不可 -->
                    <div>
                      <p class="mb-3 text-xs font-semibold uppercase tracking-wide text-surface-500">
                        {{ t('reservation.unavailability.section_label') }}
                      </p>
                      <ReservationUnavailabilityManager
                        :team-id="props.teamId"
                        :disabled="!isAdmin"
                      />
                    </div>

                    <Divider />

                    <!-- 予約通知メール宛先（機能D）: 予約成立時に通知するメール宛先の管理 -->
                    <div>
                      <p class="mb-3 text-xs font-semibold uppercase tracking-wide text-surface-500">
                        {{ t('reservation.notify_recipients.section_label') }}
                      </p>
                      <ReservationNotificationRecipientsManager
                        :team-id="props.teamId"
                        :disabled="!isAdmin"
                      />
                    </div>

                    <Divider />

                    <!-- 枠（Slot）個別管理（F03.4.5 §3.2: SlotManager をここへ格下げ移設。
                         機能・APIは無変更。例外操作（臨時営業・当日枠・長尺枠）の正規手段として残す） -->
                    <div>
                      <p class="mb-3 text-xs font-semibold uppercase tracking-wide text-surface-500">
                        {{ t('reservation.slot_manage.advanced_label') }}
                      </p>
                      <SlotManager :team-id="props.teamId" />
                    </div>
                  </div>
                </AccordionContent>
              </AccordionPanel>
            </Accordion>
          </div>
          </template>

          <!-- DEPUTY_ADMIN: 呼称設定のみ（ライン/メニュー/営業時間等の管理は ADMIN 限定のため非表示）。
               タブラベルは「{resourceName}の管理」だが副管理者は呼称のみ変更可のため、その旨を明示する。 -->
          <template v-else>
            <div class="space-y-3">
              <p class="text-sm text-surface-600 dark:text-surface-300">
                {{ t('reservation.resource_name.deputy_hint') }}
              </p>
              <ReservationResourceNameSettings
                :team-id="props.teamId"
                :disabled="!isAdminOrDeputy"
                @changed="onResourceNameChanged"
              />
            </div>
          </template>
        </TabPanel>

        <TabPanel v-if="isAdminOrDeputy" :value="3">
          <EmergencyClosureForm :team-id="props.teamId" />
        </TabPanel>
      </TabPanels>
    </Tabs>

    <ReservationForm
      v-model:visible="showBookDialog"
      :team-id="props.teamId"
      :slot-id="selectedSlot.slotId"
      :line-id="selectedSlot.lineId"
      :line-name="selectedSlot.lineName"
      :date="selectedSlot.date"
      :start-time="selectedSlot.startTime"
      :end-time="selectedSlot.endTime"
      @reserved="onReserved"
    />

    <TeamReservationGuideModal
      v-model:visible="showGuide"
      :team-id="props.teamId"
      :is-admin="isAdmin"
      :is-admin-or-deputy="isAdminOrDeputy"
      :active-tab="activeTab"
    />
  </div>
</template>
