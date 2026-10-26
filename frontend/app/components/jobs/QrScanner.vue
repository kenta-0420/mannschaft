<script setup lang="ts">
import { useQrScanner } from '~/composables/jobs/useQrScanner'
import { useGeolocation } from '~/composables/jobs/useGeolocation'
import type { JobCheckInType } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 Worker 側 QR スキャンコンポーネント。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>カメラ起動 → {@link useQrScanner#start} で QR 検出</li>
 *   <li>手動入力タブ（6 桁英数 shortCode）</li>
 *   <li>位置情報同意チェック（同意済みなら geo スナップショットを取得）</li>
 *   <li>オフラインバナー（{@code navigator.onLine} false 時）</li>
 * </ul>
 *
 * <p>検出 or 送信直前に {@code @scanned} を emit する。親は emit ペイロード経由で
 * {@code useJobCheckInApi#recordCheckIn} を叩く責務を持つ（コンポーネントは副作用を
 * 親に委ね、UI のみを担当する）。</p>
 */

interface Props {
  /** チェックイン種別（IN / OUT）。親画面が状態から算出して渡す。 */
  type: JobCheckInType
}

defineProps<Props>()

/** 親に渡すペイロード。 */
interface ScannedPayload {
  /** QR 経路なら JWT 文字列。手動入力経由なら null。 */
  token: string | null
  /** 手動入力の短コード。QR 経路なら null。 */
  shortCode: string | null
  /** 手動入力経由かどうか。 */
  manualCodeFallback: boolean
  /** スキャン時刻（ISO8601）。 */
  scannedAt: string
  /** 同意済みなら緯度経度、拒否なら null。 */
  geo: {
    lat: number | null
    lng: number | null
    accuracy: number | null
  }
  /** 端末 UA（監査用）。 */
  clientUserAgent: string | null
}

const emit = defineEmits<{
  (e: 'scanned', payload: ScannedPayload): void
}>()

const { t } = useI18n()
const scanner = useQrScanner()
const { getCurrentSnapshot } = useGeolocation()

// ============================================================
// 状態
// ============================================================

type TabKey = 'camera' | 'manual'
const activeTab = ref<TabKey>('camera')

const videoRef = ref<HTMLVideoElement | null>(null)
const cameraActive = ref(false)
const permissionExplain = ref(true)

const manualCode = ref('')
const manualCodeError = ref<string | null>(null)

const geoConsent = ref(false)
const online = ref(true)

// ============================================================
// 派生
// ============================================================

const manualCodeValid = computed(() => /^[A-Za-z0-9]{6}$/.test(manualCode.value.trim()))

// ============================================================
// カメラ制御
// ============================================================

async function startCamera() {
  if (!videoRef.value) return
  permissionExplain.value = false
  try {
    await scanner.start({
      videoEl: videoRef.value,
      onScanned: handleCameraScan,
      onError: () => {
        // UI 側は state=error で表示するのみ（started 失敗は throw される別経路）。
      },
    })
    cameraActive.value = true
  }
  catch {
    cameraActive.value = false
  }
}

function stopCamera() {
  scanner.stop()
  cameraActive.value = false
}

async function handleCameraScan(result: { text: string }) {
  stopCamera()
  const geo = await maybeCollectGeo()
  emit('scanned', {
    token: result.text,
    shortCode: null,
    manualCodeFallback: false,
    scannedAt: new Date().toISOString(),
    geo,
    clientUserAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
  })
}

// ============================================================
// 手動入力
// ============================================================

async function submitManual() {
  const code = manualCode.value.trim().toUpperCase()
  if (!/^[A-Z0-9]{6}$/.test(code)) {
    manualCodeError.value = t('jobmatching.qr.scanner.manual.invalid')
    return
  }
  manualCodeError.value = null
  const geo = await maybeCollectGeo()
  emit('scanned', {
    token: null,
    shortCode: code,
    manualCodeFallback: true,
    scannedAt: new Date().toISOString(),
    geo,
    clientUserAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
  })
}

// ============================================================
// Geolocation
// ============================================================

async function maybeCollectGeo(): Promise<ScannedPayload['geo']> {
  if (!geoConsent.value) {
    return { lat: null, lng: null, accuracy: null }
  }
  const snap = await getCurrentSnapshot({ enableHighAccuracy: true, timeout: 5000 })
  if (!snap) return { lat: null, lng: null, accuracy: null }
  return { lat: snap.latitude, lng: snap.longitude, accuracy: snap.accuracy }
}

// ============================================================
// online/offline 検知
// ============================================================

function updateOnline() {
  if (typeof navigator !== 'undefined') {
    online.value = navigator.onLine
  }
}

onMounted(() => {
  updateOnline()
  if (typeof window !== 'undefined') {
    window.addEventListener('online', updateOnline)
    window.addEventListener('offline', updateOnline)
  }
})

onBeforeUnmount(() => {
  stopCamera()
  if (typeof window !== 'undefined') {
    window.removeEventListener('online', updateOnline)
    window.removeEventListener('offline', updateOnline)
  }
})
</script>

<template>
  <div class="flex flex-col gap-4">
    <!-- オフラインバナー -->
    <div
      v-if="!online"
      class="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
      data-testid="qr-scanner-offline-banner"
      role="status"
    >
      {{ t('jobmatching.qr.scanner.offlineBanner') }}
    </div>

    <!-- タブ（カメラ / 手動） -->
    <div class="flex gap-2">
      <Button
        class="flex-1"
        :label="t('jobmatching.qr.scanner.tab.camera')"
        :severity="activeTab === 'camera' ? 'primary' : 'secondary'"
        :outlined="activeTab !== 'camera'"
        data-testid="qr-scanner-tab-camera"
        @click="activeTab = 'camera'"
      />
      <Button
        class="flex-1"
        :label="t('jobmatching.qr.scanner.tab.manual')"
        :severity="activeTab === 'manual' ? 'primary' : 'secondary'"
        :outlined="activeTab !== 'manual'"
        data-testid="qr-scanner-tab-manual"
        @click="activeTab = 'manual'"
      />
    </div>

    <!-- 位置情報同意 -->
    <label class="flex items-center gap-2 text-sm">
      <Checkbox
        v-model="geoConsent"
        binary
        input-id="qr-geo-consent"
        data-testid="qr-scanner-geo-consent"
      />
      <span>{{ t('jobmatching.qr.scanner.geoConsent') }}</span>
    </label>

    <!-- カメラタブ -->
    <section
      v-if="activeTab === 'camera'"
      class="flex flex-col items-center gap-3"
    >
      <div
        v-if="permissionExplain && !cameraActive"
        class="w-full rounded border border-surface-300 bg-surface-50 p-4 text-sm dark:border-surface-600 dark:bg-surface-800"
        data-testid="qr-scanner-permission-explain"
      >
        <p class="font-semibold">
          {{ t('jobmatching.qr.scanner.permission.title') }}
        </p>
        <p class="mt-1 text-surface-600 dark:text-surface-300">
          {{ t('jobmatching.qr.scanner.permission.body') }}
        </p>
      </div>

      <div
        class="relative aspect-square w-[80vw] max-w-[480px] overflow-hidden rounded-xl border border-surface-300 bg-black dark:border-surface-600"
      >
        <video
          ref="videoRef"
          class="h-full w-full object-cover"
          playsinline
          muted
          data-testid="qr-scanner-video"
        />
        <div
          v-if="!cameraActive"
          class="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/60 text-white"
        >
          <i class="pi pi-camera text-4xl" />
          <p class="text-sm">
            {{ t('jobmatching.qr.scanner.cameraInactive') }}
          </p>
        </div>
      </div>

      <div class="flex w-full justify-center gap-2">
        <Button
          v-if="!cameraActive"
          :label="t('jobmatching.qr.scanner.startCamera')"
          icon="pi pi-camera"
          data-testid="qr-scanner-start"
          @click="startCamera"
        />
        <Button
          v-else
          :label="t('jobmatching.qr.scanner.stopCamera')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          @click="stopCamera"
        />
      </div>

      <p
        v-if="scanner.errorMessage.value"
        class="text-sm text-red-600"
        role="alert"
      >
        {{ t('jobmatching.qr.scanner.error.cameraFailed', { detail: scanner.errorMessage.value }) }}
      </p>
    </section>

    <!-- 手動入力タブ -->
    <section
      v-else-if="activeTab === 'manual'"
      class="flex flex-col items-center gap-3"
    >
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('jobmatching.qr.scanner.manual.description') }}
      </p>
      <InputText
        v-model="manualCode"
        class="w-full max-w-[240px] text-center font-mono text-2xl tracking-[0.3em]"
        maxlength="6"
        :placeholder="t('jobmatching.qr.scanner.manual.placeholder')"
        data-testid="qr-scanner-manual-input"
        @input="manualCodeError = null"
      />
      <p
        v-if="manualCodeError"
        class="text-xs text-red-600"
        role="alert"
        data-testid="qr-scanner-manual-error"
      >
        {{ manualCodeError }}
      </p>
      <Button
        :label="t('jobmatching.qr.scanner.manual.submit')"
        :disabled="!manualCodeValid"
        data-testid="qr-scanner-manual-submit"
        @click="submitManual"
      />
    </section>
  </div>
</template>
