<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

const showCreateTeamDialog = ref(false)
const showCreateOrgDialog = ref(false)

onMounted(async () => {
  await Promise.all([
    teamStore.fetchMyTeams(),
    orgStore.fetchMyOrganizations(),
  ])
})

function onTeamCreated(entity: { id: number; name: string }) {
  teamStore.fetchMyTeams()
  navigateTo(`/teams/${entity.id}`)
}

function onOrgCreated(entity: { id: number; name: string }) {
  orgStore.fetchMyOrganizations()
  navigateTo(`/organizations/${entity.id}`)
}

const templateLabel: Record<string, string> = {
  SPORTS: 'スポーツ',
  CLINIC: 'クリニック',
  SCHOOL: '学校',
  COMMUNITY: 'コミュニティ',
  COMPANY: '企業',
  OTHER: 'その他',
}

const orgTypeLabel: Record<string, string> = {
  NONPROFIT: '非営利',
  FORPROFIT: '営利',
}
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <h1 class="mb-8 text-2xl font-bold">
      ダッシュボード
    </h1>

    <!-- マイチーム セクション -->
    <section class="mb-10">
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-xl font-semibold">
          <i class="pi pi-users mr-2" />マイチーム
        </h2>
        <Button
          label="チームを作成"
          icon="pi pi-plus"
          size="small"
          @click="showCreateTeamDialog = true"
        />
      </div>

      <div v-if="teamStore.loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 40px; height: 40px" />
      </div>

      <div v-else-if="teamStore.myTeams.length === 0" class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500">
        <i class="pi pi-inbox mb-2 text-3xl" />
        <p>まだチームに参加していません</p>
        <Button
          label="チームを作成"
          icon="pi pi-plus"
          text
          class="mt-3"
          @click="showCreateTeamDialog = true"
        />
      </div>

      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="team in teamStore.myTeams"
          :key="team.id"
          class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
          @click="navigateTo(`/teams/${team.id}`)"
        >
          <div class="mb-2 flex items-center gap-3">
            <Avatar
              :image="team.iconUrl ?? undefined"
              :label="team.iconUrl ? undefined : team.name.charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ team.nickname1 || team.name }}
              </h3>
              <Tag :value="templateLabel[team.template] ?? team.template" severity="info" class="text-xs" />
            </div>
          </div>
          <div class="flex items-center justify-between text-sm text-gray-500">
            <RoleBadge :role="team.role" />
          </div>
        </div>
      </div>
    </section>

    <!-- マイ組織 セクション -->
    <section>
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-xl font-semibold">
          <i class="pi pi-building mr-2" />マイ組織
        </h2>
        <Button
          label="組織を作成"
          icon="pi pi-plus"
          size="small"
          @click="showCreateOrgDialog = true"
        />
      </div>

      <div v-if="orgStore.loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 40px; height: 40px" />
      </div>

      <div v-else-if="orgStore.myOrganizations.length === 0" class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500">
        <i class="pi pi-inbox mb-2 text-3xl" />
        <p>まだ組織に参加していません</p>
        <Button
          label="組織を作成"
          icon="pi pi-plus"
          text
          class="mt-3"
          @click="showCreateOrgDialog = true"
        />
      </div>

      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="org in orgStore.myOrganizations"
          :key="org.id"
          class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
          @click="navigateTo(`/organizations/${org.id}`)"
        >
          <div class="mb-2 flex items-center gap-3">
            <Avatar
              :image="org.iconUrl ?? undefined"
              :label="org.iconUrl ? undefined : org.name.charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ org.nickname1 || org.name }}
              </h3>
              <Tag :value="orgTypeLabel[org.orgType] ?? org.orgType" severity="secondary" class="text-xs" />
            </div>
          </div>
          <div class="flex items-center justify-between text-sm text-gray-500">
            <RoleBadge :role="org.role" />
          </div>
        </div>
      </div>
    </section>

    <!-- 作成ダイアログ -->
    <EntityCreateDialog
      entity-type="team"
      :visible="showCreateTeamDialog"
      @update:visible="showCreateTeamDialog = $event"
      @created="onTeamCreated"
    />
    <EntityCreateDialog
      entity-type="organization"
      :visible="showCreateOrgDialog"
      @update:visible="showCreateOrgDialog = $event"
      @created="onOrgCreated"
    />
  </div>
</template>
