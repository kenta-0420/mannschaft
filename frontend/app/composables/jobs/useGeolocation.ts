/**
 * F13.1 Phase 13.1.2 位置情報取得 composable。
 *
 * <p>Worker 側 QR スキャン時に勤務地との乖離検知用に現在位置を 1 回だけ取得する。
 * ユーザーが位置情報利用を拒否した場合や {@code navigator.geolocation} が使えない場合は
 * {@code null} を返す（BE 側は geoLat/geoLng が null でも受け付ける）。</p>
 */

export interface GeolocationSnapshot {
  latitude: number
  longitude: number
  /** 精度（メートル）。ブラウザ次第で欠落する可能性はないが保険として optional。 */
  accuracy: number | null
  /** 取得時刻（Date.now() 相当）。 */
  takenAt: number
}

export interface GeolocationOptions {
  /** デフォルト true。高精度モード（GPS を使わせる）。 */
  enableHighAccuracy?: boolean
  /** デフォルト 5000ms。 */
  timeout?: number
  /** デフォルト 0（常に最新位置を取る）。キャッシュ許容ミリ秒。 */
  maximumAge?: number
}

/**
 * 位置情報を 1 回だけ取得する。拒否・非対応・タイムアウト・エラーの場合は {@code null}。
 *
 * <p>HTTPS or localhost でしか geolocation は使えない点に注意（E2E でも localhost なので OK）。</p>
 */
export async function getCurrentSnapshot(
  options: GeolocationOptions = {},
): Promise<GeolocationSnapshot | null> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) {
    return null
  }
  const enableHighAccuracy = options.enableHighAccuracy ?? true
  const timeout = options.timeout ?? 5000
  const maximumAge = options.maximumAge ?? 0
  return new Promise<GeolocationSnapshot | null>((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        resolve({
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: typeof pos.coords.accuracy === 'number' ? pos.coords.accuracy : null,
          takenAt: Date.now(),
        })
      },
      () => {
        // 拒否 / タイムアウト / 位置取得不能 — いずれも null にフォールバック。
        resolve(null)
      },
      { enableHighAccuracy, timeout, maximumAge },
    )
  })
}

/**
 * composable 形式でも使えるようラッパを提供する（useXxx 命名で import 側を統一するため）。
 */
export function useGeolocation() {
  return {
    getCurrentSnapshot,
  }
}
