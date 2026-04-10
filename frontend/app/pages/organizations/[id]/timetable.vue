<script setup lang="ts">
import type { TimetablePeriod, TimetableTerm } from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const timetableApi = useTimetableApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

const periods = ref<TimetablePeriod[]>([])
const terms = ref<TimetableTerm[]>([])
const loading = ref(true)
const activeTab = ref('0')

// 学期追加ダイアログ
const showTermDialog = ref(false)
const termSubmitting = ref(false)
const termForm = ref({
  name: '',
  startDate: '',
  endDate: '',
  academicYear: new Date().getFullYear(),
  sortOrder: 0,
})

// 学期削除
const deletingTermId = ref<number | null>(null)

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const [p, t] = await Promise.all([
      timetableApi.listPeriods(orgId.value),
      timetableApi.listTerms('organization', orgId.value),
    ])
    periods.value = p
    terms.value = t
  } catch {
    notification.error(t('timetable.load_error'))
  } finally {
    loading.value = false
  }
}

async function submitTerm() {
  if (!termForm.value.name || !termForm.value.startDate || !termForm.value.endDate) return
  termSubmitting.value = true
  try {
    await timetableApi.createOrgTerm(orgId.value, {
      name: termForm.value.name,
      startDate: termForm.value.startDate,
      endDate: termForm.value.endDate,
      academicYear: termForm.value.academicYear,
      sortOrder: termForm.value.sortOrder,
    })
    notification.success(t('timetable.term_success'))
    showTermDialog.value = false
    termForm.value = {
      name: '',
      startDate: '',
      endDate: '',
      academicYear: new Date().getFullYear(),
      sortOrder: 0,
    }
    terms.value = await timetableApi.listTerms('organization', orgId.value)
  } catch {
    notification.error(t('timetable.term_error'))
  } finally {
    termSubmitting.value = false
  }
}

async function deleteTerm(termId: number) {
  deletingTermId.value = termId
  try {
    await timetableApi.deleteTerm(termId)
    notification.success(t('common.dialog.success'))
    terms.value = await timetableApi.listTerms('organization', orgId.value)
  } catch {
    notification.error(t('common.dialog.error'))
  } finally {
    deletingTermId.value = null
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <h1 class="mb-6 text-2xl font-bold">{{ $t('timetable.org_title') }}</h1>

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
            <div class="mb-4 flex items-center justify-between">
              <p class="text-sm text-surface-500">{{ $t('timetable.term_hint') }}</p>
              <Button
                v-if="isAdmin"
                :label="$t('timetable.add_term')"
                icon="pi pi-plus"
                size="small"
                @click="showTermDialog = true"
              />
            </div>
            <DataTable :value="terms" data-key="id" striped-rows>
              <template #empty>
                <div class="py-8 text-center text-surface-500">
                  {{ $t('timetable.no_term') }}
                </div>
              </template>
              <Column field="academicYear" :header="$t('timetable.term_academic_year')" style="width: 80px" />
              <Column field="name" :header="$t('timetable.term_name')" />
              <Column :header="$t('timetable.term_start')">
                <template #body="{ data }">
                  {{ new Date(data.startDate).toLocaleDateString('ja-JP') }}
                </template>
              </Column>
              <Column :header="$t('timetable.term_end')">
                <template #body="{ data }">
                  {{ new Date(data.endDate).toLocaleDateString('ja-JP') }}
                </template>
              </Column>
              <Column
                v-if="isAdmin"
                :header="$t('common.label.actions')"
                style="width: 80px"
              >
                <template #body="{ data }">
                  <Button
                    icon="pi pi-trash"
                    size="small"
                    text
                    severity="danger"
                    :loading="deletingTermId === data.id"
                    @click="deleteTerm(data.id)"
                  />
                </template>
              </Column>
            </DataTable>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- 学期追加ダイアログ -->
      <Dialog
        v-model:visible="showTermDialog"
        :header="$t('timetable.add_term')"
        :modal="true"
        class="w-full max-w-md"
      >
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term_academic_year') }}</label>
            <InputNumber v-model="termForm.academicYear" class="w-full" :min="2000" :max="2100" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term_name') }}</label>
            <InputText
              v-model="termForm.name"
              class="w-full"
              :placeholder="$t('timetable.term_name_placeholder')"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term_start') }}</label>
            <InputText v-model="termForm.startDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term_end') }}</label>
            <InputText v-model="termForm.endDate" type="date" class="w-full" />
          </div>
        </div>
        <template #footer>
          <Button
            :label="$t('common.button.cancel')"
            severity="secondary"
            @click="showTermDialog = false"
          />
          <Button
            :label="$t('timetable.add_term')"
            icon="pi pi-check"
            :loading="termSubmitting"
            :disabled="!termForm.name || !termForm.startDate || !termForm.endDate"
            @click="submitTerm"
          />
        </template>
      </Dialog>
    </template>
  </div>
</template>
