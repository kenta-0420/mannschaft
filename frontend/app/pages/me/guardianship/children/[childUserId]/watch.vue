<script setup lang="ts">
/**
 * F08.9 件2 保護者による子データ閲覧専用見守り画面（FE）。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/05_guardian_child_view.md
 *
 * 4面（① 今後の予定 ② 出欠状況 ③ 所属チーム/組織 ④ お知らせ）＋ 代理操作履歴（件3・代理マーク土台）を
 * 閲覧専用で表示する。書き込み・操作系ボタンは一切置かない（後見切替＝acting-as とは別の軽量導線）。
 *
 * 認可は BE 側で GuardianshipSwitchService.evaluateSwitch を再利用（12歳未満のみ許可）。
 * - 403 GUARDIANSHIP_SWITCH_AGE_LOCKED: 12歳以上（自立段階）で対象外
 * - 403 GUARDIANSHIP_LINK_NOT_FOUND: 保護者リンクなし（列挙不可のため他人の子も同一エラー）
 * いずれも握りつぶさず、専用の案内 UI で正直に表示する（対処療法禁止）。
 */
import dayjs from 'dayjs'
import type {
  ChildCalendarEntry,
  ChildAttendanceStats,
  GuardianChildMembershipsResponse,
  GuardianAnnouncementItem,
  GuardianProxyActionItem,
} from '~/types/guardianship'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t, te } = useI18n()
const guardianshipApi = useGuardianshipApi()
const notification = useNotification()
const { captureQuiet } = useErrorReport()
const { formatDateTime, formatTime } = useDatetime()

const childUserId = computed(() => Number(route.params.childUserId))
// switch.vue の子一覧からのリンクに displayName を渡している場合のみ表示（専用の名前解決 API は持たない）。
const childDisplayName = computed(() => {
  const raw = route.query.displayName
  return typeof raw === 'string' && raw.length > 0 ? raw : null
})

const loading = ref(true)
const showGuide = ref(false)

/** BE の 403 errorCode を握りつぶさず表示種別へ写像する（対処療法禁止・障害対応の原則）。 */
const forbiddenReason = ref<'AGE_LOCKED' | 'LINK_NOT_FOUND' | null>(null)
/** 上記以外の想定外エラー（ネットワーク断・5xx 等）。トーストに加えて画面上にも明示する。 */
const loadFailed = ref(false)

const schedules = ref<ChildCalendarEntry[]>([])
const attendanceStats = ref<ChildAttendanceStats | null>(null)
const memberships = ref<GuardianChildMembershipsResponse | null>(null)
const announcements = ref<GuardianAnnouncementItem[]>([])
const proxyActions = ref<GuardianProxyActionItem[]>([])

function extractErrorCode(err: unknown): string | null {
  return (err as { data?: { error?: { code?: string } } })?.data?.error?.code ?? null
}

function attendanceStatusLabel(status: string | undefined): string {
  if (!status) return ''
  const key = `proxy.guardianship.watch.attendanceStatus.${status}`
  return te(key) ? t(key) : status
}

function featureScopeLabel(scope: string | undefined): string {
  if (!scope) return ''
  const key = `proxy.scope.${scope.toLowerCase()}`
  return te(key) ? t(key) : scope
}

function inputSourceLabel(source: string | undefined): string {
  if (!source) return ''
  const key = `proxy.desk.inputSource.${source.toLowerCase()}`
  return te(key) ? t(key) : source
}

async function load() {
  loading.value = true
  forbiddenReason.value = null
  loadFailed.value = false
  try {
    // ①今後の予定: 本日〜30日後。②出欠状況: 直近90日〜本日（schedule 系既存 EP と同一のナイーブ LocalDateTime 文字列）。
    const now = dayjs()
    const scheduleFrom = now.startOf('day').format('YYYY-MM-DDTHH:mm:ss')
    const scheduleTo = now.add(30, 'day').endOf('day').format('YYYY-MM-DDTHH:mm:ss')
    const statsFrom = now.subtract(90, 'day').startOf('day').format('YYYY-MM-DDTHH:mm:ss')
    const statsTo = now.endOf('day').format('YYYY-MM-DDTHH:mm:ss')

    const [schedulesRes, statsRes, membershipsRes, announcementsRes, proxyRes] = await Promise.all([
      guardianshipApi.getChildSchedules(childUserId.value, scheduleFrom, scheduleTo),
      guardianshipApi.getChildAttendanceStats(childUserId.value, statsFrom, statsTo),
      guardianshipApi.getChildMemberships(childUserId.value),
      guardianshipApi.getChildAnnouncements(childUserId.value, 0, 20),
      guardianshipApi.getChildProxyActions(childUserId.value),
    ])

    schedules.value = schedulesRes.data ?? []
    attendanceStats.value = statsRes.data ?? null
    memberships.value = membershipsRes.data ?? null
    announcements.value = announcementsRes.data?.items ?? []
    proxyActions.value = proxyRes.data?.items ?? []
  } catch (err) {
    const code = extractErrorCode(err)
    if (code === 'GUARDIANSHIP_SWITCH_AGE_LOCKED') {
      forbiddenReason.value = 'AGE_LOCKED'
    } else if (code === 'GUARDIANSHIP_LINK_NOT_FOUND') {
      forbiddenReason.value = 'LINK_NOT_FOUND'
    } else {
      // 想定外のエラーは症状を隠さず、画面上にも明示したうえでトースト表示する。
      loadFailed.value = true
      captureQuiet(err, { context: 'GuardianWatch: 見守りデータ取得失敗' })
      notification.error(t('proxy.guardianship.watch.loadError'))
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader
      :title="$t('proxy.guardianship.watch.title')"
      back-to="/me/guardianship/switch"
      help
      @help="showGuide = true"
    >
      <Tag v-if="childDisplayName" :value="childDisplayName" severity="secondary" />
    </PageHeader>

    <Message severity="info" :closable="false" class="mb-6">
      {{ $t('proxy.guardianship.watch.readonlyNotice') }}
    </Message>

    <PageLoading v-if="loading" />

    <!-- 403: 対象外（年齢封印 or リンク無し）。握りつぶさず理由別に案内する -->
    <SectionCard v-else-if="forbiddenReason">
      <div class="flex flex-col items-center gap-3 py-8 text-center">
        <i class="pi pi-lock text-3xl text-surface-400" aria-hidden="true" />
        <h2 class="text-lg font-semibold text-surface-700 dark:text-surface-200">
          {{
            forbiddenReason === 'AGE_LOCKED'
              ? $t('proxy.guardianship.watch.forbidden.ageLocked.title')
              : $t('proxy.guardianship.watch.forbidden.linkNotFound.title')
          }}
        </h2>
        <p class="max-w-md text-sm text-surface-500">
          {{
            forbiddenReason === 'AGE_LOCKED'
              ? $t('proxy.guardianship.watch.forbidden.ageLocked.body')
              : $t('proxy.guardianship.watch.forbidden.linkNotFound.body')
          }}
        </p>
      </div>
    </SectionCard>

    <!-- 想定外エラー: 症状を隠さず再試行導線を出す -->
    <SectionCard v-else-if="loadFailed">
      <div class="flex flex-col items-center gap-3 py-8 text-center">
        <i class="pi pi-exclamation-triangle text-3xl text-surface-400" aria-hidden="true" />
        <p class="text-sm text-surface-500">{{ $t('proxy.guardianship.watch.loadError') }}</p>
        <Button :label="$t('proxy.guardianship.switch.startButton')" icon="pi pi-refresh" text @click="load" />
      </div>
    </SectionCard>

    <template v-else>
      <div class="flex flex-col gap-6">
        <!-- ① 今後の予定 -->
        <SectionCard :title="$t('proxy.guardianship.watch.sections.schedules.title')">
          <div v-if="schedules.length > 0" class="flex flex-col gap-2">
            <div
              v-for="s in schedules"
              :key="s.id ?? `${s.content?.title}-${s.time?.startAt}`"
              class="flex items-center justify-between rounded-lg border border-surface-100 px-3 py-2 dark:border-surface-700"
            >
              <div class="min-w-0">
                <p class="truncate font-medium text-surface-800 dark:text-surface-100">
                  {{ s.content?.title }}
                </p>
                <p class="text-xs text-surface-500">
                  <span v-if="s.scope?.scopeName">{{ s.scope.scopeName }} ・ </span>
                  {{ formatDateTime(s.time?.startAt) }}
                  <template v-if="s.time?.endAt"> 〜 {{ formatTime(s.time.endAt) }}</template>
                </p>
              </div>
              <Tag
                v-if="s.myAttendanceStatus"
                :value="attendanceStatusLabel(s.myAttendanceStatus)"
                severity="secondary"
                class="shrink-0"
              />
            </div>
          </div>
          <DashboardEmptyState
            v-else
            icon="pi pi-calendar"
            :message="$t('proxy.guardianship.watch.sections.schedules.empty')"
          />
        </SectionCard>

        <!-- ② 出欠状況 -->
        <SectionCard :title="$t('proxy.guardianship.watch.sections.attendance.title')">
          <div v-if="attendanceStats && attendanceStats.totalSchedules" class="grid grid-cols-2 gap-4 text-center sm:grid-cols-4">
            <div>
              <p class="text-2xl font-bold text-surface-800 dark:text-surface-100">{{ attendanceStats.attended ?? 0 }}</p>
              <p class="text-xs text-surface-500">{{ $t('proxy.guardianship.watch.sections.attendance.attended') }}</p>
            </div>
            <div>
              <p class="text-2xl font-bold text-surface-800 dark:text-surface-100">{{ attendanceStats.partial ?? 0 }}</p>
              <p class="text-xs text-surface-500">{{ $t('proxy.guardianship.watch.sections.attendance.partial') }}</p>
            </div>
            <div>
              <p class="text-2xl font-bold text-surface-800 dark:text-surface-100">{{ attendanceStats.absent ?? 0 }}</p>
              <p class="text-xs text-surface-500">{{ $t('proxy.guardianship.watch.sections.attendance.absent') }}</p>
            </div>
            <div>
              <p class="text-2xl font-bold text-primary">
                {{ Math.round(attendanceStats.attendanceRate ?? 0) }}%
              </p>
              <p class="text-xs text-surface-500">{{ $t('proxy.guardianship.watch.sections.attendance.rate') }}</p>
            </div>
          </div>
          <DashboardEmptyState
            v-else
            icon="pi pi-chart-bar"
            :message="$t('proxy.guardianship.watch.sections.attendance.empty')"
          />
        </SectionCard>

        <!-- ③ 所属チーム/組織 -->
        <SectionCard :title="$t('proxy.guardianship.watch.sections.memberships.title')">
          <div
            v-if="(memberships?.teams?.length ?? 0) > 0 || (memberships?.organizations?.length ?? 0) > 0"
            class="flex flex-col gap-3"
          >
            <div v-if="memberships?.teams?.length">
              <p class="mb-1 text-xs font-medium uppercase tracking-wider text-surface-400">
                {{ $t('proxy.guardianship.watch.sections.memberships.teams') }}
              </p>
              <div class="flex flex-wrap gap-2">
                <Tag v-for="team in memberships.teams" :key="team.scopeId" :value="team.name ?? `ID: ${team.scopeId}`" />
              </div>
            </div>
            <div v-if="memberships?.organizations?.length">
              <p class="mb-1 text-xs font-medium uppercase tracking-wider text-surface-400">
                {{ $t('proxy.guardianship.watch.sections.memberships.organizations') }}
              </p>
              <div class="flex flex-wrap gap-2">
                <Tag
                  v-for="org in memberships.organizations"
                  :key="org.scopeId"
                  :value="org.name ?? `ID: ${org.scopeId}`"
                  severity="info"
                />
              </div>
            </div>
          </div>
          <DashboardEmptyState
            v-else
            icon="pi pi-users"
            :message="$t('proxy.guardianship.watch.sections.memberships.empty')"
          />
        </SectionCard>

        <!-- ④ お知らせ -->
        <SectionCard :title="$t('proxy.guardianship.watch.sections.announcements.title')">
          <div v-if="announcements.length > 0" class="flex flex-col gap-2">
            <div
              v-for="a in announcements"
              :key="a.threadId"
              class="rounded-lg border border-surface-100 px-3 py-2 dark:border-surface-700"
            >
              <div class="flex items-center justify-between gap-2">
                <p class="truncate font-medium text-surface-800 dark:text-surface-100">{{ a.title }}</p>
                <Tag v-if="a.priority === 'IMPORTANT'" :value="a.priority" severity="warn" class="shrink-0" />
              </div>
              <p class="text-xs text-surface-500">
                <span v-if="a.scopeName">{{ a.scopeName }} ・ </span>{{ formatDateTime(a.updatedAt) }}
              </p>
            </div>
          </div>
          <DashboardEmptyState
            v-else
            icon="pi pi-megaphone"
            :message="$t('proxy.guardianship.watch.sections.announcements.empty')"
          />
        </SectionCard>

        <!-- 代理操作履歴（件3・代理マーク土台） -->
        <SectionCard :title="$t('proxy.guardianship.watch.proxyActions.title')">
          <div v-if="proxyActions.length > 0" class="flex flex-col gap-2">
            <div
              v-for="p in proxyActions"
              :key="p.id"
              class="rounded-lg border border-surface-100 px-3 py-2 dark:border-surface-700"
            >
              <div class="flex flex-wrap items-center gap-2">
                <Tag :value="$t('proxy.guardianship.watch.proxyActions.badge')" severity="warn" />
                <span class="text-sm font-medium text-surface-800 dark:text-surface-100">
                  {{ featureScopeLabel(p.featureScope) }}
                </span>
              </div>
              <p class="mt-1 text-xs text-surface-500">
                {{ inputSourceLabel(p.inputSource) }} ・ {{ formatDateTime(p.createdAt) }}
              </p>
            </div>
          </div>
          <DashboardEmptyState
            v-else
            icon="pi pi-user-edit"
            :message="$t('proxy.guardianship.watch.proxyActions.empty')"
          />
        </SectionCard>
      </div>
    </template>

    <!-- 使い方説明モーダル -->
    <GuardianWatchGuideModal v-model:visible="showGuide" />
  </div>
</template>
