<script setup lang="ts">
import type { SwitchableChild, BlockedChild } from '~/types/guardianship'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const guardianshipApi = useGuardianshipApi()
const guardianshipSwitchStore = useGuardianshipSwitchStore()
const notification = useNotification()
const router = useRouter()

// データ
const children = ref<SwitchableChild[]>([])
const blockedChildren = ref<BlockedChild[]>([])
const loading = ref(false)

// 確認ダイアログ
const showConfirmDialog = ref(false)
const selectedChild = ref<SwitchableChild | null>(null)
const submitting = ref(false)

// 切替終了処理
const ending = ref(false)

async function loadChildren() {
  loading.value = true
  try {
    const res = await guardianshipApi.listSwitchableChildren()
    children.value = res.data.children
    blockedChildren.value = res.data.blockedChildren
  } finally {
    loading.value = false
  }
}

function openConfirmDialog(child: SwitchableChild) {
  selectedChild.value = child
  showConfirmDialog.value = true
}

async function handleStartSwitch() {
  if (!selectedChild.value) return
  submitting.value = true
  try {
    await guardianshipApi.startSwitch(selectedChild.value.childUserId)
    guardianshipSwitchStore.startSwitch({
      childUserId: selectedChild.value.childUserId,
      displayName: selectedChild.value.displayName,
    })
    showConfirmDialog.value = false
    notification.success(t('proxy.guardianship.switch.success'))
    await router.push('/dashboard')
  } finally {
    submitting.value = false
  }
}

async function handleEndSwitch() {
  if (!guardianshipSwitchStore.activeChild) return
  ending.value = true
  try {
    await guardianshipApi.endSwitch(guardianshipSwitchStore.activeChild.childUserId)
    guardianshipSwitchStore.endSwitch()
    notification.success(t('proxy.guardianship.switch.endSuccess'))
  } finally {
    ending.value = false
  }
}

onMounted(loadChildren)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">{{ $t('proxy.guardianship.switch.title') }}</h1>

    <!-- 現在切替中バナー -->
    <div
      v-if="guardianshipSwitchStore.isActingAs"
      class="mb-6 flex items-center justify-between rounded-lg bg-orange-100 border border-orange-300 px-4 py-3"
    >
      <span class="text-orange-800 text-sm font-medium">
        {{ $t('proxy.guardianship.switch.actingAs', { name: guardianshipSwitchStore.activeChild?.displayName ?? '' }) }}
      </span>
      <Button
        :label="$t('proxy.guardianship.switch.end')"
        severity="warning"
        size="small"
        :loading="ending"
        @click="handleEndSwitch"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <template v-else>
      <!-- 切替可能な子 -->
      <div v-if="children.length > 0" class="flex flex-col gap-4 mb-8">
        <div
          v-for="child in children"
          :key="child.childUserId"
          class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-0 px-5 py-4 shadow-sm"
        >
          <div>
            <p class="font-semibold text-surface-800">
              {{ child.displayName ?? `ID: ${child.childUserId}` }}
            </p>
            <p v-if="child.stageKey" class="mt-0.5 text-xs text-surface-500">
              {{ $t(`proxy.guardianship.stage.${child.stageKey}`, child.stageKey) }}
            </p>
          </div>
          <div class="flex items-center gap-2">
            <!-- 自立移行状況リンク -->
            <NuxtLink
              :to="`/me/guardianship/children/${child.childUserId}/independence`"
              class="inline-flex items-center justify-center w-8 h-8 rounded-full text-surface-500 hover:bg-surface-100 transition-colors"
              v-tooltip.top="$t('proxy.guardianship.independence.title')"
            >
              <i class="pi pi-info-circle" />
            </NuxtLink>
            <!-- 切替ボタン -->
            <Button
              :label="$t('proxy.guardianship.switch.startAs', { name: child.displayName ?? `ID: ${child.childUserId}` })"
              severity="primary"
              size="small"
              :disabled="guardianshipSwitchStore.isActingAs && guardianshipSwitchStore.activeChild?.childUserId === child.childUserId"
              @click="openConfirmDialog(child)"
            />
          </div>
        </div>
      </div>

      <!-- 切替可能な子がいない場合 -->
      <div
        v-else-if="!loading && children.length === 0 && blockedChildren.length === 0"
        class="rounded-lg bg-surface-50 border border-surface-200 px-6 py-10 text-center text-surface-500"
      >
        {{ $t('proxy.guardianship.switch.empty') }}
      </div>

      <!-- 封印された子（グレーアウト） -->
      <div v-if="blockedChildren.length > 0" class="flex flex-col gap-3">
        <p class="text-xs font-medium text-surface-400 uppercase tracking-wider mb-1">{{ $t('proxy.guardianship.switch.otherChildren') }}</p>
        <div
          v-for="child in blockedChildren"
          :key="child.childUserId"
          class="flex items-center justify-between rounded-xl border border-surface-100 bg-surface-50 px-5 py-4 opacity-60"
        >
          <div>
            <p class="font-semibold text-surface-500">
              {{ child.displayName ?? `ID: ${child.childUserId}` }}
            </p>
            <p v-if="child.stageKey" class="mt-0.5 text-xs text-surface-400">
              {{ $t(`proxy.guardianship.stage.${child.stageKey}`, child.stageKey) }}
            </p>
          </div>
          <Tag severity="secondary" :value="$t('proxy.guardianship.switch.blocked')" />
        </div>
      </div>
    </template>

    <!-- 確認ダイアログ -->
    <Dialog
      v-model:visible="showConfirmDialog"
      modal
      :header="$t('proxy.guardianship.switch.title')"
      :style="{ width: '24rem' }"
    >
      <p class="text-surface-700 text-sm leading-relaxed">
        {{ $t('proxy.guardianship.switch.confirm') }}
      </p>
      <p v-if="selectedChild" class="mt-3 font-semibold text-surface-800">
        {{ selectedChild.displayName ?? `ID: ${selectedChild.childUserId}` }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="$t('proxy.guardianship.switch.cancelButton')"
            severity="secondary"
            text
            @click="showConfirmDialog = false"
          />
          <Button
            :label="$t('proxy.guardianship.switch.startButton')"
            severity="primary"
            :loading="submitting"
            @click="handleStartSwitch"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
