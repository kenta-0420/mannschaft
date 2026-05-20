/**
 * k6 環境変数設定ファイル（ローカル Docker Compose 用）
 *
 * 実行時に環境変数で上書き可能:
 *   BASE_URL=http://localhost:8080 k6 run scenarios/01_auth.js
 *   または docker-compose.k6.yml の environment セクションで設定
 */

// Spring Boot API のベース URL
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * テスト用ユーザー認証情報。
 * ローカルの Docker Compose 環境のシードデータに合わせて設定すること。
 * 本番環境では絶対に使用しないこと。
 */
export const TEST_USER = {
  email: __ENV.TEST_USER_EMAIL || 'k6test@example.com',
  password: __ENV.TEST_USER_PASSWORD || 'Password1!',
};

/**
 * テスト対象のサンプル ID（ローカル DB に存在するものを指定）
 */
export const SAMPLE_IDS = {
  teamId: __ENV.SAMPLE_TEAM_ID || '1',
  orgId: __ENV.SAMPLE_ORG_ID || '1',
};
