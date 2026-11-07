<script setup lang="ts">
import type { TimetablePeriod, TimetableTerm } from '~/types/timetable'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
const timetableApi = useTimetableApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgSlug)

const periods = ref<TimetablePeriod[]>([])
const terms = ref<TimetableTerm[]>([])
const loading = ref(true)
const activeTab = ref('0')
/**
 * 時限・学期それぞれの取得状態。取得失敗を「定義されていません」の空状態と区別するために持つ
 * （FRONTEND_CODING_CONVENTION の「取得失敗を空状態へフォールバックさせてはならない」）。
 */
const periodsLoadState = ref<'loading' | 'error' | 'loaded'>('loading')
const termsLoadState = ref<'loading' | 'error' | 'loaded'>('loading')

/** 時限一覧の取得。初回表示と再試行で共通に使う。 */
async function loadPeriods() {
  periodsLoadState.value = 'loading'
  try {
    periods.value = await timetableApi.listPeriods(orgSlug.value)
    periodsLoadState.value = 'loaded'
  } catch {
    periodsLoadState.value = 'error'
    notification.error(t('timetable.load_error'))
  }
}

/** 学期一覧の取得。初回表示・再試行・追加削除後の取り直しで共通に使う。 */
async function loadTerms() {
  termsLoadState.value = 'loading'
  try {
    terms.value = await timetableApi.listTerms('organization', orgSlug.value)
    termsLoadState.value = 'loaded'
  } catch {
    termsLoadState.value = 'error'
    notification.error(t('timetable.term_load_error'))
  }
}

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    await Promise.all([loadPeriods(), loadTerms()])
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader :title="$t('timetable.org_title')" />

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">{{ $t('timetable.tab_period') }}</Tab>
          <Tab value="1" data-testid="org-timetable-tab-term">{{ $t('timetable.tab_term') }}</Tab>
        </TabList>
        <TabPanels>
          <!-- 時限定義タブ -->
          <TabPanel value="0">
            <p class="mb-4 text-sm text-surface-500">{{ $t('timetable.period_hint') }}</p>
            <DashboardErrorState
              v-if="periodsLoadState === 'error'"
              :message="$t('timetable.load_error')"
              testid="period-load-error-state"
              @retry="loadPeriods"
            />
            <DataTable v-else :value="periods" data-key="periodNumber" striped-rows>
              <template #empty>
                <div class="py-8 text-center text-surface-500">
                  {{ $t('timetable.no_period') }}
                </div>
              </template>
              <Column
                field="periodNumber"
                :header="$t('timetable.period_number')"
                style="width: 80px"
              />
              <Column field="label" :header="$t('timetable.period_label')" />
              <Column field="startTime" :header="$t('timetable.period_start')" style="width: 100px" />
              <Column field="endTime" :header="$t('timetable.period_end')" style="width: 100px" />
              <Column :header="$t('timetable.period_is_break')" style="width: 80px">
                <template #body="{ data }">
                  <i v-if="data.isBreak" class="pi pi-check text-green-500" />
                </template>
              </Column>
            </DataTable>
          </TabPanel>

          <!-- 学期テンプレートタブ -->
          <TabPanel value="1">
            <TimetableTermManager
              scope-type="organization"
              :scope-slug="orgSlug"
              :terms="terms"
              :can-manage="isAdmin"
              :hint="$t('timetable.term_hint')"
              :load-state="termsLoadState"
              @changed="loadTerms"
              @retry="loadTerms"
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
