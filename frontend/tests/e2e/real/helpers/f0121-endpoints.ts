/**
 * F01.2.1 実機E2E の接続先の既定値（1 か所に集約し、専用設定 playwright-f0121.config.ts と
 * 共通土台 f0121-org-groups-world.ts の両方がここを参照する）。
 *
 * 検証用 BE は 8081、検証用 FE は 3021（3001 は他セッションの検証 FE と衝突しやすいため）。
 * 本陣 8080/3000 は使わない。環境変数 API_BASE_URL / BASE_URL で上書きできる。
 */
export const F0121_DEFAULT_API = 'http://localhost:8081'
export const F0121_DEFAULT_FE = 'http://localhost:3021'

export const API = process.env.API_BASE_URL || F0121_DEFAULT_API
export const FE = process.env.BASE_URL || F0121_DEFAULT_FE

/** 専用設定から実行されていることの印（globalTeardown による後始末が保証される実行だけを許す）。 */
export const F0121_CONFIG_MARK = 'F0121_DEDICATED_CONFIG'
