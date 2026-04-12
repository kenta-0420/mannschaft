<script setup lang="ts">
import type { UserViolationHistoryResponse } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const users = ref<Record<string, unknown>[]>([])
const loading = ref(true)
const totalRecords = ref(0)
const page = ref(0)
const showViolationDialog = ref(false)
const violationHistory = ref<UserViolationHistoryResponse | null>(null)
const selectedUserId = ref<number>(0)

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getDashboardUsers({ page: page.value, size: 20 })
    users.value = res.data
    totalRecords.value = users.value.length
  } catch {
    showError('ユーザー一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function viewViolations(userId: number) {
  selectedUserId.value = userId
  try {
    const res = await systemAdminApi.getUserViolations(userId)
    violationHistory.value = res.data
    showViolationDialog.value = true
  } catch {
    showError('違反履歴の取得に失敗しました')
  }
}

async function unflagYabai(userId: number) {
  try {
    await systemAdminApi.unflagYabaiUser(userId)
    success('やばいフラグを解除しました')
    if (violationHistory.value) {
      violationHistory.value.yabai = false
    }
  } catch {
    showError('フラグ解除に失敗しました')
  }
}

function onPage(event: { page: number }) {
  page.value = event.page
  load()
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader title="ユーザー管理" />

    <DataTable
      :value="users"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="20"
      :total-records="totalRecords"
      :first="page * 20"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">ユーザーがありません</div>
      </template>
      <Column header="ID" style="width: 60px">
        <template #body="{ data }">
          <span class="text-xs text-surface-500">#{{ data.id }}</span>
        </template>
      </Column>
      <Column header="名前">
        <template #body="{ data }">
          {{ data.displayName ?? data.name ?? '-' }}
        </template>
      </Column>
      <Column header="メール">
        <template #body="{ data }">
          {{ data.email ?? '-' }}
        </template>
      </Column>
      <Column header="ロール" style="width: 100px">
        <template #body="{ data }">
          <Tag v-if="data.role" :value="String(data.role)" severity="info" />
          <span v-else>-</span>
        </template>
      </Column>
      <Column header="登録日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{
            data.createdAt ? String(data.createdAt).substring(0, 10) : '-'
          }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 140px">
        <template #body="{ data }">
          <Button
            label="違反履歴"
            size="small"
            severity="warn"
            text
            @click="viewViolations(Number(data.id))"
          />
        </template>
      </Column>
    </DataTable>

    <!-- 違反履歴ダイアログ -->
    <Dialog
      v-model:visible="showViolationDialog"
      header="違反履歴"
      :style="{ width: '600px' }"
      modal
    >
      <div v-if="violationHistory" class="flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-3">
          <Card>
            <template #content>
              <p class="text-xs text-surface-500">有効警告数</p>
              <p class="text-xl font-bold text-yellow-500">
                {{ violationHistory.activeWarningCount }}
              </p>
            </template>
          </Card>
          <Card>
            <template #content>
              <p class="text-xs text-surface-500">コンテンツ削除数</p>
              <p class="text-xl font-bold text-red-500">
                {{ violationHistory.activeContentDeleteCount }}
              </p>
            </template>
          </Card>
          <Card>
            <template #content>
              <p class="text-xs text-surface-500">違反合計</p>
              <p class="text-xl font-bold text-primary">
                {{ violationHistory.totalViolationCount }}
              </p>
            </template>
          </Card>
          <Card>
            <template #content>
              <p class="text-xs text-surface-500">やばいフラグ</p>
              <div class="flex items-center gap-2">
                <Tag
                  :value="violationHistory.yabai ? 'ON' : 'OFF'"
                  :severity="violationHistory.yabai ? 'danger' : 'success'"
                />
                <Button
                  v-if="violationHistory.yabai"
                  label="解除"
                  size="small"
                  severity="success"
                  @click="unflagYabai(selectedUserId)"
                />
              </div>
            </template>
          </Card>
        </div>

        <div v-if="violationHistory.violations.length">
          <h3 class="mb-2 text-sm font-semibold">違反一覧</h3>
          <DataTable :value="violationHistory.violations" striped-rows data-key="id">
            <Column field="violationType" header="種別" style="width: 120px" />
            <Column field="reason" header="理由" />
            <Column header="有効" style="width: 80px">
              <template #body="{ data }">
                <Tag
                  :value="data.isActive ? '有効' : '無効'"
                  :severity="data.isActive ? 'danger' : 'secondary'"
                />
              </template>
            </Column>
            <Column header="日時" style="width: 140px">
              <template #body="{ data }">
                <span class="text-sm">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</span>
              </template>
            </Column>
          </DataTable>
        </div>
        <p v-else class="text-center text-surface-400">違反履歴はありません</p>
      </div>
    </Dialog>
  </div>
</template>
