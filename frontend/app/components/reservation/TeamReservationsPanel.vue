<script setup lang="ts">
import type { ReservationSettingsResponse } from '~/types/reservation'

const props = defineProps<{ teamId: string }>()

const { t } = useI18n()
const { isAdmin, isAdminOrDeputy, isMember, roleName, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

const activeTab = ref(0)
const showBookDialog = ref(false)
const selectedSlot = ref({ slotId: 0, lineId: 0, lineName: '', date: '', startTime: '', endTime: '' })

/** 詳細設定アコーディオンの開閉状態（初期 collapsed） */
const advancedSettingsValue = ref<string | null>(null)

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
    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">{{ t('reservation.tab.book') }}</Tab>
        <Tab :value="1">{{ t('reservation.tab.list') }}</Tab>
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
            <SlotPicker :team-id="props.teamId" @slot-selected="onSlotSelected" />
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
            :team-id="props.teamId"
            :can-manage="true"
            mode="team"
          />
          <ReservationList
            v-else
            :team-id="props.teamId"
            :can-manage="false"
            mode="mine"
          />
        </TabPanel>

        <!-- ライン管理タブ（ADMIN限定）+ 枠管理 + 詳細設定アコーディオン -->
        <TabPanel v-if="isAdmin" :value="2">
          <LineManager :team-id="props.teamId" />

          <!-- 枠（Slot）管理セクション -->
          <div class="mt-6">
            <SlotManager :team-id="props.teamId" />
          </div>

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
    />
  </div>
</template>
