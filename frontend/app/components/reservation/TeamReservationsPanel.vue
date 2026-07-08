<script setup lang="ts">
import type { ReservationSettingsResponse, ReservationLineResponse } from '~/types/reservation'
import type { components } from '~/types/generated'

type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']
type SlotTemplateResponse = components['schemas']['SlotTemplateResponse']

const props = defineProps<{ teamId: string }>()

const { t } = useI18n()
const { isAdmin, isAdminOrDeputy, isMember, roleName, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

const activeTab = ref(0)
/** 使い方ガイドモーダルの表示状態。 */
const showGuide = ref(false)

type BookDisplayMode = 'list' | 'grid' | 'matrix'
/** 表示選好の localStorage キー（ADHD配慮: 毎回選ばせない・F03.4.4 §5.4）。 */
const BOOK_DISPLAY_MODE_STORAGE_KEY = 'mannschaft.reservation.bookDisplayMode'

/** 予約タブの表示切替: マトリックス（既定・機能H SlotMatrixPicker）／グリッド（機能C）／リスト（既存 SlotPicker）。 */
const bookDisplayMode = ref<BookDisplayMode>(loadStoredBookDisplayMode())

function loadStoredBookDisplayMode(): BookDisplayMode {
  if (!import.meta.client) return 'matrix'
  const stored = localStorage.getItem(BOOK_DISPLAY_MODE_STORAGE_KEY)
  return stored === 'list' || stored === 'grid' || stored === 'matrix' ? stored : 'matrix'
}

watch(bookDisplayMode, (mode) => {
  if (import.meta.client) localStorage.setItem(BOOK_DISPLAY_MODE_STORAGE_KEY, mode)
})

const bookDisplayOptions = computed(() => [
  { label: t('reservation.matrix.view_matrix'), value: 'matrix' as const },
  { label: t('reservation.matrix.view_staff_grid'), value: 'grid' as const },
  { label: t('reservation.matrix.view_list'), value: 'list' as const },
])
const showBookDialog = ref(false)
const selectedSlot = ref({ slotId: 0, lineId: 0, lineName: '', date: '', startTime: '', endTime: '' })

/** 予約直後の再読込用 ref。既存 MatchRequestList 等と同一パターン（defineExpose({ refresh })）。 */
type Refreshable = { refresh: () => void | Promise<void> }
const slotPickerRef = ref<Refreshable | null>(null)
const slotGridPickerRef = ref<Refreshable | null>(null)
const slotMatrixPickerRef = ref<Refreshable | null>(null)
const reservationListRef = ref<Refreshable | null>(null)

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
const slotTemplateManagerRef = ref<{ refresh: () => Promise<void>; items: SlotTemplateResponse[] } | null>(null)

const lineCount = computed(() => lineManagerRef.value?.items?.length ?? 0)
const menuCount = computed(() => menuManagerRef.value?.items?.length ?? 0)
const templateCount = computed(() => slotTemplateManagerRef.value?.items?.length ?? 0)

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
  slotPickerRef.value?.refresh()
  slotGridPickerRef.value?.refresh()
  slotMatrixPickerRef.value?.refresh()
  reservationListRef.value?.refresh()
}

/**
 * 予約一覧タブでの操作（承認/却下/キャンセル、ReservationList の @changed）直後に、
 * 予約するタブ（マトリックス/グリッド/リスト）の空き表示を再読込する（#2179「予約→一覧」の逆方向）。
 * 一覧タブが非アクティブでも各 Picker はマウント済み（TabPanels 非 lazy）のため refresh は常に有効。
 * ReservationList 自身は既に loadReservations() 済みのため reservationListRef は対象外。
 */
function onSlotsChanged() {
  slotPickerRef.value?.refresh()
  slotGridPickerRef.value?.refresh()
  slotMatrixPickerRef.value?.refresh()
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

onMounted(async () => {
  await loadPermissions()
  await loadReservationSettings()
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
        <Tab v-if="isAdmin" :value="2">{{ t('reservation.tab.line_manage') }}</Tab>
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
            <div class="mb-3 flex justify-end">
              <SelectButton
                v-model="bookDisplayMode"
                :options="bookDisplayOptions"
                option-label="label"
                option-value="value"
                :allow-empty="false"
              />
            </div>
            <!-- 表示切替の再マウント/再取得を防ぐ KeepAlive（状態・スクロール位置・取得済みデータを保持）。
                 各 Picker の ref/emit 結線（onReserved 等）は KeepAlive 配下でも維持される。 -->
            <KeepAlive>
              <SlotMatrixPicker
                v-if="bookDisplayMode === 'matrix'"
                ref="slotMatrixPickerRef"
                :team-id="props.teamId"
                :is-admin="isAdmin"
                @slot-selected="onSlotSelected"
                @manage-lines="activeTab = 2"
                @reserved="onReserved"
              />
              <SlotGridPicker
                v-else-if="bookDisplayMode === 'grid'"
                ref="slotGridPickerRef"
                :team-id="props.teamId"
                :is-admin="isAdmin"
                @slot-selected="onSlotSelected"
                @manage-lines="activeTab = 2"
              />
              <SlotPicker
                v-else
                ref="slotPickerRef"
                :team-id="props.teamId"
                :is-admin="isAdmin"
                @slot-selected="onSlotSelected"
                @manage-lines="activeTab = 2"
              />
            </KeepAlive>
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

        <!-- ライン管理タブ（ADMIN限定）+ メニュー管理 + 週間テンプレート + 枠管理 + 詳細設定アコーディオン -->
        <TabPanel v-if="isAdmin" :value="2">
          <!-- 管理セクション群（初期は全閉・ADHD配慮で脳内摩擦削減。件数バッジ付き） -->
          <!-- 注意: AccordionContent に lazy を付けると閉状態の子が非マウントになり、件数バッジ（子の defineExpose({ items }) 参照）が機能しなくなる。常時マウント前提のため lazy 禁止 -->
          <Accordion v-model:value="managementAccordionValue" multiple>
            <AccordionPanel value="lines">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.management.section_count', { label: t('reservation.line_manage_title'), n: lineCount }) }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <LineManager ref="lineManagerRef" :team-id="props.teamId" />
              </AccordionContent>
            </AccordionPanel>

            <!-- メニュー管理セクション（機能E・F03.4.1） -->
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

            <!-- 週間テンプレート管理セクション（F03.4.2） -->
            <AccordionPanel value="templates">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.management.section_count', { label: t('reservation.template.title'), n: templateCount }) }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <SlotTemplateManager ref="slotTemplateManagerRef" :team-id="props.teamId" />
              </AccordionContent>
            </AccordionPanel>

            <!-- 枠（Slot）管理セクション。日付フィルター済み一覧のため件数バッジは付けない（全体件数と誤認させないため） -->
            <AccordionPanel value="slots">
              <AccordionHeader>
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ t('reservation.slot_manager.title') }}
                </span>
              </AccordionHeader>
              <AccordionContent>
                <SlotManager :team-id="props.teamId" />
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
                  </div>
                </AccordionContent>
              </AccordionPanel>
            </Accordion>
          </div>
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
      :is-admin="isAdmin"
      :is-admin-or-deputy="isAdminOrDeputy"
      :active-tab="activeTab"
    />
  </div>
</template>
