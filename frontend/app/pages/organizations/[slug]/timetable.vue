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

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const [p, t] = await Promise.all([
      timetableApi.listPeriods(orgSlug.value),
      timetableApi.listTerms('organization', orgSlug.value),
    ])
    periods.value = p
    terms.value = t
  } catch {
    notification.error(t('timetable.load_error'))
  } finally {
    loading.value = false
  }
}

async function reloadTerms() {
  try {
    terms.value = await timetableApi.listTerms('organization', orgSlug.value)
  } catch {
    notification.error(t('timetable.term_load_error'))
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
          <Tab value="1">{{ $t('timetable.tab_term') }}</Tab>
        </TabList>
        <TabPanels>
          <!-- 時限定義タブ -->
          <TabPanel value="0">
            <p class="mb-4 text-sm text-surface-500">{{ $t('timetable.period_hint') }}</p>
            <DataTable :value="periods" data-key="periodNumber" striped-rows>
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
              @changed="reloadTerms"
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
