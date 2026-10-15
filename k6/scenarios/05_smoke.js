/**
 * シナリオ 05: スモークテスト
 *
 * 全シナリオを軽量に一通り実行して「システムが最低限動作する」ことを確認する。
 * CI パイプラインや負荷テスト開始前のサニティチェックとして使用する。
 *
 * カバー範囲:
 *   1. POST /api/v1/auth/login                    — 認証
 *   2. GET  /api/v1/teams/{id}/schedules?from&to  — チームスケジュール一覧（認証あり）
 *   3. GET  /api/v1/organizations/{id}/schedules  — 組織スケジュール一覧（認証あり）
 *   4. GET  /api/v1/teams/{id}                    — チーム詳細（認証あり）
 *   5. GET  /api/v1/public/teams/{id}             — 公開チームページ（認証不要）
 *   6. GET  /api/v1/public/organizations/{id}     — 公開組織ページ（認証不要）
 *
 * 実行例（最も手軽なコマンド）:
 *   k6 run k6/scenarios/05_smoke.js
 *   k6 run --env BASE_URL=http://localhost:8080 k6/scenarios/05_smoke.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { login, authGetHeaders } from '../lib/auth.js';
import { BASE_URL, SAMPLE_IDS } from '../config/env.local.js';
import { dateRange } from '../lib/helpers.js';

export const options = {
  vus: 1,
  duration: '1m',
  thresholds: {
    http_req_duration: ['p(95)<3000'],  // スモークはゆるめの 3 秒
    http_req_failed: ['rate<0.05'],     // エラー率 5% 未満（サーバー未起動時を除く）
  },
};

export default function () {
  // ===== Step 1: ログイン =====
  const token = login();
  if (!token) {
    console.warn('ログイン失敗 — Spring Boot が起動しているか確認してください: ' + BASE_URL);
    sleep(5);
    return;
  }

  const headers = authGetHeaders(token);
  const { from, to } = dateRange(30, 30);

  sleep(1);

  // ===== Step 2: チームスケジュール一覧（認証あり） =====
  const teamSchedRes = http.get(
    `${BASE_URL}/api/v1/teams/${SAMPLE_IDS.teamId}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    { ...headers, tags: { name: 'smoke_team_schedules' } }
  );
  check(teamSchedRes, {
    'smoke: team schedules accessible': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });

  sleep(1);

  // ===== Step 3: 組織スケジュール一覧（認証あり） =====
  const orgSchedRes = http.get(
    `${BASE_URL}/api/v1/organizations/${SAMPLE_IDS.orgId}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    { ...headers, tags: { name: 'smoke_org_schedules' } }
  );
  check(orgSchedRes, {
    'smoke: org schedules accessible': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });

  sleep(1);

  // ===== Step 4: チーム詳細（認証あり） =====
  const teamRes = http.get(
    `${BASE_URL}/api/v1/teams/${SAMPLE_IDS.teamId}`,
    { ...headers, tags: { name: 'smoke_team_detail' } }
  );
  check(teamRes, {
    'smoke: team detail accessible': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });

  sleep(1);

  // ===== Step 5: 公開チームページ（認証不要） =====
  const pubTeamRes = http.get(
    `${BASE_URL}/api/v1/public/teams/${SAMPLE_IDS.teamId}`,
    { headers: { Accept: 'application/json' }, tags: { name: 'smoke_public_team' } }
  );
  check(pubTeamRes, {
    'smoke: public team page accessible': (r) => r.status !== 500,
  });

  sleep(1);

  // ===== Step 6: 公開組織ページ（認証不要） =====
  const pubOrgRes = http.get(
    `${BASE_URL}/api/v1/public/organizations/${SAMPLE_IDS.orgId}`,
    { headers: { Accept: 'application/json' }, tags: { name: 'smoke_public_org' } }
  );
  check(pubOrgRes, {
    'smoke: public org page accessible': (r) => r.status !== 500,
  });

  sleep(1);
}
