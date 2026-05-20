/**
 * シナリオ 01: 認証（ログイン・トークンリフレッシュ）
 *
 * 対象エンドポイント:
 *   POST /api/v1/auth/login
 *     - リクエスト: { email, password, rememberMe, deviceFingerprint }
 *     - レスポンス: ApiResponse<TokenResponse> { data: { accessToken, tokenType: "Bearer" } }
 *   POST /api/v1/auth/refresh  （Cookie: refresh_token が必要）
 *
 * 目的: ログイン処理の並列耐性・レスポンスタイムを計測する。
 *
 * 実行例:
 *   k6 run k6/scenarios/01_auth.js
 *   k6 run --env BASE_URL=http://localhost:8080 k6/scenarios/01_auth.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, TEST_USER } from '../config/env.local.js';
import { randomSleep } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '30s', target: 10 },  // ウォームアップ: 10 VU まで増加
    { duration: '1m', target: 50 },   // 通常負荷: 50 VU を 1 分間維持
    { duration: '30s', target: 0 },   // クールダウン: 0 VU まで減少
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95%tile が 2 秒以内
    http_req_failed: ['rate<0.01'],     // エラー率 1% 未満
  },
};

export default function () {
  // ログイン
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      email: TEST_USER.email,
      password: TEST_USER.password,
      rememberMe: false,
      deviceFingerprint: null,
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'auth_login' },
    }
  );

  check(loginRes, {
    'login: status 200': (r) => r.status === 200,
    'login: has accessToken': (r) => {
      try {
        return r.json('data.accessToken') !== null;
      } catch (_) {
        return false;
      }
    },
    'login: tokenType is Bearer': (r) => {
      try {
        return r.json('data.tokenType') === 'Bearer';
      } catch (_) {
        return false;
      }
    },
  });

  randomSleep(0.5, 1.5);
}
