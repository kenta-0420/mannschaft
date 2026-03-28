<script setup lang="ts">
import type { OrganizationEntity } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const organizations = ref<OrganizationEntity[]>([])
const loading = ref(true)
const totalRecords = ref(0)
const page = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getDashboardOrganizations({ page: page.value, size: 20 })
    const data = res.data
    organizations.value = data.content ?? (data as unknown as OrganizationEntity[])
    totalRecords.value = data.totalElements ?? organizations.value.length
  } catch {
    showError('組織一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function freeze(id: number) {
  try {
    await systemAdminApi.freezeOrganization(id)
    success('組織を凍結しました')
    await load()
  } catch {
    showError('凍結に失敗しました')
  }
}

async function unfreeze(id: number) {
  try {
    await systemAdminApi.unfreezeOrganization(id)
    success('組織の凍結を解除しました')
    await load()
  } catch {
    showError('凍結解除に失敗しました')
  }
}

function orgTypeName(type: string) {
  switch (type) {
    case 'SCHOOL':
      return '学校'
    case 'COMPANY':
      return '企業'
    case 'NPO':
      return 'NPO'
    case 'COMMUNITY':
      return 'コミュニティ'
    case 'GOVERNMENT':
      return '行政'
    case 'OTHER':
      return 'その他'
    default:
      return type
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
    <h1 class="mb-6 text-2xl font-bold">組織管理</h1>

    <DataTable
      :value="organizations"
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
        <div class="py-8 text-center text-surface-500">組織がありません</div>
      </template>
      <Column field="name" header="組織名" />
      <Column header="種別" style="width: 100px">
        <template #body="{ data }">
          {{ orgTypeName(data.orgType) }}
        </template>
      </Column>
      <Column header="地域" style="width: 140px">
        <template #body="{ data }">
          {{ data.prefecture }}{{ data.city ? ` ${data.city}` : '' }}
        </template>
      </Column>
      <Column header="公開" style="width: 80px">
        <template #body="{ data }">
          <Tag
            :value="data.visibility === 'PUBLIC' ? '公開' : '非公開'"
            :severity="data.visibility === 'PUBLIC' ? 'success' : 'secondary'"
          />
        </template>
      </Column>
      <Column header="状態" style="width: 100px">
        <template #body="{ data }">
          <Tag v-if="data.archivedAt" value="凍結中" severity="danger" />
          <Tag v-else-if="data.deletedAt" value="削除済" severity="secondary" />
          <Tag v-else value="有効" severity="success" />
        </template>
      </Column>
      <Column header="登録日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{ data.createdAt?.substring(0, 10) }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 160px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="!data.archivedAt"
              label="凍結"
              size="small"
              severity="danger"
              @click="freeze(data.id)"
            />
            <Button
              v-else
              label="凍結解除"
              size="small"
              severity="success"
              @click="unfreeze(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>
  </div>
</template>
