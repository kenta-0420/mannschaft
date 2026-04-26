<script setup lang="ts">
import type { CareLinkResponse, CareLinkNotifySettingsRequest } from '~/types/careLink'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = useCareLinkApi()
const notification = useNotification()

const activeTab = ref(0)

// データ
const watchers = ref<CareLinkResponse[]>([])
const recipients = ref<CareLinkResponse[]>([])
const pendingInvitations = ref<CareLinkResponse[]>([])
const loading = ref(false)

// 通知設定ダイアログ
const showNotifyDialog = ref(false)
const selectedLink = ref<CareLinkResponse | null>(null)
const notifyForm = ref<CareLinkNotifySettingsRequest>({})

// 削除確認ダイアログ
const showDeleteDialog = ref(false)
const deletingLinkId = ref<number | null>(null)
const deleting = ref(false)

async function loadAll() {
  loading.value = true
  try {
    const [w, r, p] = await Promise.all([
      api.getMyWatchers(),
      api.getMyRecipients(),
      api.getMyInvitations(),
    ])
    watchers.value = w.data
    recipients.value = r.data
    pendingInvitations.value = p.data
  } catch {
    notification.error(t('care.message.loadError'))
  } finally {
    loading.value = false
  }
}

function openNotifyDialog(link: CareLinkResponse) {
  selectedLink.value = link
  notifyForm.value = {
    notifyOnRsvp: link.notifyOnRsvp,
    notifyOnCheckin: link.notifyOnCheckin,
    notifyOnCheckout: link.notifyOnCheckout,
    notifyOnAbsentAlert: link.notifyOnAbsentAlert,
    notifyOnDismissal: link.notifyOnDismissal,
  }
  showNotifyDialog.value = true
}

const savingNotify = ref(false)

async function saveNotifySettings() {
  if (!selectedLink.value) return
  savingNotify.value = true
  try {
    await api.updateNotifySettings(selectedLink.value.id, notifyForm.value)
    notification.success(t('care.message.updateNotifySuccess'))
    showNotifyDialog.value = false
    await loadAll()
  } catch {
    notification.error(t('care.message.updateNotifyError'))
  } finally {
    savingNotify.value = false
  }
}

function openDeleteDialog(linkId: number) {
  deletingLinkId.value = linkId
  showDeleteDialog.value = true
}

async function deleteLink() {
  if (!deletingLinkId.value) return
  deleting.value = true
  try {
    await api.deleteCareLink(deletingLinkId.value)
    notification.success(t('care.message.deleteLinkSuccess'))
    showDeleteDialog.value = false
    deletingLinkId.value = null
    await loadAll()
  } catch {
    notification.error(t('care.message.deleteLinkError'))
  } finally {
    deleting.value = false
  }
}

function getStatusSeverity(status: string): 'success' | 'warn' | 'danger' | 'secondary' {
  const map: Record<string, 'success' | 'warn' | 'danger' | 'secondary'> = {
    ACTIVE: 'success',
    PENDING: 'warn',
    REJECTED: 'danger',
    REVOKED: 'secondary',
  }
  return map[status] ?? 'secondary'
}

onMounted(() => loadAll())
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('care.page.title')" class="mb-4" />

    <div class="mb-4 flex gap-2">
      <Button
        :label="$t('care.button.inviteWatcher')"
        icon="pi pi-user-plus"
        @click="$router.push('/me/care-links/invite-watcher')"
      />
      <Button
        :label="$t('care.button.inviteRecipient')"
        icon="pi pi-heart"
        severity="secondary"
        @click="$router.push('/me/care-links/invite-recipient')"
      />
    </div>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab :value="0">
            {{ $t('care.tab.watchers') }}
            <Badge v-if="watchers.length > 0" :value="watchers.length" class="ml-1" />
          </Tab>
          <Tab :value="1">
            {{ $t('care.tab.recipients') }}
            <Badge v-if="recipients.length > 0" :value="recipients.length" class="ml-1" />
          </Tab>
          <Tab :value="2">
            {{ $t('care.tab.pending') }}
            <Badge
              v-if="pendingInvitations.length > 0"
              :value="pendingInvitations.length"
              severity="warn"
              class="ml-1"
            />
          </Tab>
        </TabList>

        <TabPanels>
          <!-- 見守り者タブ -->
          <TabPanel :value="0">
            <div
              v-if="watchers.length === 0"
              class="rounded border border-dashed p-8 text-center text-surface-400"
            >
              {{ $t('care.label.noWatchers') }}
            </div>
            <div v-else class="flex flex-col gap-3 pt-3">
              <div
                v-for="link in watchers"
                :key="link.id"
                class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
              >
                <div class="flex items-start justify-between gap-2">
                  <div>
                    <div class="font-semibold">
                      {{ link.watcherDisplayName }}
                    </div>
                    <div class="mt-1 flex flex-wrap gap-2 text-sm text-surface-500">
                      <span>{{ $t(`care.category.${link.careCategory}`) }}</span>
                      <span>·</span>
                      <span>{{ $t(`care.relationship.${link.relationship}`) }}</span>
                      <Tag
                        v-if="link.isPrimary"
                        :value="$t('care.label.isPrimary')"
                        severity="info"
                        rounded
                      />
                    </div>
                  </div>
                  <Tag
                    :value="$t(`care.status.${link.status}`)"
                    :severity="getStatusSeverity(link.status)"
                    rounded
                  />
                </div>
                <div class="mt-3 flex gap-2">
                  <Button
                    :label="$t('care.label.notifySettings')"
                    icon="pi pi-bell"
                    size="small"
                    severity="secondary"
                    @click="openNotifyDialog(link)"
                  />
                  <Button
                    :label="$t('care.button.deleteLink')"
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    outlined
                    @click="openDeleteDialog(link.id)"
                  />
                </div>
              </div>
            </div>
          </TabPanel>

          <!-- ケア対象者タブ -->
          <TabPanel :value="1">
            <div
              v-if="recipients.length === 0"
              class="rounded border border-dashed p-8 text-center text-surface-400"
            >
              {{ $t('care.label.noRecipients') }}
            </div>
            <div v-else class="flex flex-col gap-3 pt-3">
              <div
                v-for="link in recipients"
                :key="link.id"
                class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
              >
                <div class="flex items-start justify-between gap-2">
                  <div>
                    <div class="font-semibold">
                      {{ link.careRecipientDisplayName }}
                    </div>
                    <div class="mt-1 flex flex-wrap gap-2 text-sm text-surface-500">
                      <span>{{ $t(`care.category.${link.careCategory}`) }}</span>
                      <span>·</span>
                      <span>{{ $t(`care.relationship.${link.relationship}`) }}</span>
                    </div>
                  </div>
                  <Tag
                    :value="$t(`care.status.${link.status}`)"
                    :severity="getStatusSeverity(link.status)"
                    rounded
                  />
                </div>
                <div class="mt-3 flex gap-2">
                  <Button
                    :label="$t('care.label.notifySettings')"
                    icon="pi pi-bell"
                    size="small"
                    severity="secondary"
                    @click="openNotifyDialog(link)"
                  />
                  <Button
                    :label="$t('care.button.deleteLink')"
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    outlined
                    @click="openDeleteDialog(link.id)"
                  />
                </div>
              </div>
            </div>
          </TabPanel>

          <!-- 保留中の招待タブ -->
          <TabPanel :value="2">
            <div
              v-if="pendingInvitations.length === 0"
              class="rounded border border-dashed p-8 text-center text-surface-400"
            >
              {{ $t('care.label.noPendingInvitations') }}
            </div>
            <div v-else class="flex flex-col gap-3 pt-3">
              <div
                v-for="inv in pendingInvitations"
                :key="inv.id"
                class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
              >
                <div class="flex items-start justify-between gap-2">
                  <div>
                    <div class="font-semibold">
                      {{ inv.watcherDisplayName || inv.careRecipientDisplayName }}
                    </div>
                    <div class="mt-1 flex flex-wrap gap-2 text-sm text-surface-500">
                      <span>{{ $t(`care.category.${inv.careCategory}`) }}</span>
                      <span>·</span>
                      <span>{{ $t(`care.relationship.${inv.relationship}`) }}</span>
                      <span>·</span>
                      <span>{{ $t('care.label.invitedBy') }}: {{ $t(`care.invitedBy.${inv.invitedBy}`) }}</span>
                    </div>
                  </div>
                  <Tag
                    :value="$t('care.status.PENDING')"
                    severity="warn"
                    rounded
                  />
                </div>
                <div class="mt-3 flex gap-2">
                  <Button
                    :label="$t('care.button.deleteLink')"
                    icon="pi pi-times"
                    size="small"
                    severity="danger"
                    outlined
                    @click="openDeleteDialog(inv.id)"
                  />
                </div>
              </div>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <!-- 通知設定ダイアログ -->
    <Dialog
      v-model:visible="showNotifyDialog"
      :header="$t('care.label.notifySettings')"
      modal
      :style="{ width: '400px' }"
    >
      <div class="flex flex-col gap-4 py-2">
        <div class="flex items-center justify-between">
          <label class="text-sm">{{ $t('care.label.notifyOnRsvp') }}</label>
          <ToggleSwitch v-model="notifyForm.notifyOnRsvp" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm">{{ $t('care.label.notifyOnCheckin') }}</label>
          <ToggleSwitch v-model="notifyForm.notifyOnCheckin" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm">{{ $t('care.label.notifyOnCheckout') }}</label>
          <ToggleSwitch v-model="notifyForm.notifyOnCheckout" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm">{{ $t('care.label.notifyOnAbsentAlert') }}</label>
          <ToggleSwitch v-model="notifyForm.notifyOnAbsentAlert" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm">{{ $t('care.label.notifyOnDismissal') }}</label>
          <ToggleSwitch v-model="notifyForm.notifyOnDismissal" />
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('common.button.cancel')"
          severity="secondary"
          @click="showNotifyDialog = false"
        />
        <Button
          :label="$t('care.button.updateNotify')"
          :loading="savingNotify"
          @click="saveNotifySettings"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      :header="$t('care.dialog.deleteLinkTitle')"
      modal
      :style="{ width: '400px' }"
    >
      <p class="text-sm text-surface-600 dark:text-surface-400">
        {{ $t('care.dialog.deleteLinkConfirm') }}
      </p>
      <template #footer>
        <Button
          :label="$t('common.button.cancel')"
          severity="secondary"
          @click="showDeleteDialog = false"
        />
        <Button
          :label="$t('care.button.deleteLink')"
          severity="danger"
          :loading="deleting"
          @click="deleteLink"
        />
      </template>
    </Dialog>
  </div>
</template>
