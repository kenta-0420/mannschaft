/**
 * シナリオ 02: スケジュール系（一覧・詳細取得）
 *
 * 対象エンドポイント:
 *   GET /api/v1/teams/{teamId}/schedules?from=...&to=...
 *     - 認証必須（Bearer トークン）
 *     - from/to は ISO 8601 形式 (yyyy-MM-ddTHH:mm:ss)
 *   GET /api/v1/organizations/{orgId}/schedules?from=...&to=...
 *     - 認証必須（Bearer トークン）
 *
 * 目的: チーム・組織スケジュール取得の負荷耐性を計測する。
 *   スケジュールは多くのユーザーが頻繁に参照する重要なエンドポイント。
 *
 * 実行例:
 *   k6 run k6/scenarios/02_schedule.js
 *   k6 run --env SAMPLE_TEAM_ID=1 --env SAMPLE_ORG_ID=1 k6/scenarios/02_schedule.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { login, authGetHeaders } from '../lib/auth.js';
import { BASE_URL, SAMPLE_IDS } from '../config/env.local.js';
import { dateRange, randomSleep } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '2m', target: 30 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1500'],  // スケジュール取得は 1.5 秒以内目標
    http_req_failed: ['rate<0.01'],
  },
};

// VU 初期化: ログインしてトークンを取得（VU 起動時に 1 回だけ実行）
export function setup() {
  const token = login();
  if (!token) {
    console.error('setup: ログイン失敗 — テスト用ユーザーが存在するか確認してください');
  }
  return { token };
}

export default function (data) {
  const { token } = data;
  if (!token) {
    return;
  }

  const headers = authGetHeaders(token);
  const { from, to } = dateRange(30, 30);

  // チームスケジュール一覧 GET /api/v1/teams/{teamId}/schedules
  const teamSchedRes = http.get(
    `${BASE_URL}/api/v1/teams/${SAMPLE_IDS.teamId}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    {
      ...headers,
      tags: { name: 'team_schedules_list' },
    }
  );
  check(teamSchedRes, {
    'team schedules: status 200 or 403': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
    'team schedules: response time < 1500ms': (r) => r.timings.duration < 1500,
  });

  randomSleep(0.5, 1.0);

  // 組織スケジュール一覧 GET /api/v1/organizations/{orgId}/schedules
  const orgSchedRes = http.get(
    `${BASE_URL}/api/v1/organizations/${SAMPLE_IDS.orgId}/schedules?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    {
      ...headers,
      tags: { name: 'org_schedules_list' },
    }
  );
  check(orgSchedRes, {
    'org schedules: status 200 or 403': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
    'org schedules: response time < 1500ms': (r) => r.timings.duration < 1500,
  });

  randomSleep(0.5, 1.5);
}
