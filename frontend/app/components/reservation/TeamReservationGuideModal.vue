<script setup lang="ts">
/**
 * チーム予約パネルの使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は TeamReservationGuideContent に分離し、activeTab（開いていたタブ）に連動して
 * そのタブの実務手順を表示する（isAdmin/isAdminOrDeputy で予約一覧タブの内容を出し分け）。
 */
const props = defineProps<{
  teamId: string
  isAdmin: boolean
  /** 予約一覧タブの管理者向け内容を出すかどうか（DEPUTY_ADMIN も含む）。 */
  isAdminOrDeputy: boolean
  /** モーダルを開いたときの TeamReservationsPanel のアクティブタブ（0=予約する/1=予約一覧/2=予約対象の管理/3=緊急休業）。 */
  activeTab: number
}>()
const visible = defineModel<boolean>('visible', { default: false })

const { t } = useI18n()

/** 呼称の動的差し込み（F03.4.5 §5.2）: タブ2のヘッダー・ガイド本文（Content）双方で使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))
watch(visible, (v) => { if (v) void loadResourceName() })

/** タブ番号 → 既存 reservation.tab.* ラベルキー（Panel のタブ表記と一致させる）。 */
const TAB_LABEL_KEYS: Record<number, string> = {
  0: 'reservation.tab.book',
  1: 'reservation.tab.list',
  2: 'reservation.tab.line_manage',
  3: 'reservation.tab.emergency_closure',
}

/**
 * モーダルのヘッダーに「予約の使い方（現在のタブ名）」を表示し、どのタブの説明かを明確にする。
 * タブ1 は #2198 で非管理者向けに「自分の予約」へ改名されたため、Panel のタブ表記と同じ条件で出し分ける。
 */
const headerLabel = computed(() => {
  const tabKey = props.activeTab === 1 && !props.isAdminOrDeputy
    ? 'reservation.tab.my_reservations'
    : (TAB_LABEL_KEYS[props.activeTab] ?? 'reservation.tab.book')
  return `${t('reservation.team_guide.title')}（${t(tabKey, { resourceName: resourceName.value })}）`
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="headerLabel"
    class="w-full max-w-2xl"
    data-testid="team-reservation-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <TeamReservationGuideContent
        :is-admin="isAdmin"
        :is-admin-or-deputy="isAdminOrDeputy"
        :active-tab="activeTab"
        :resource-name="resourceName"
      />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
