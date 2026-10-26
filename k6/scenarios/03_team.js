/**
 * シナリオ 03: チーム情報取得（認証必須）
 *
 * 対象エンドポイント:
 *   GET /api/v1/teams/{id}        — チーム詳細取得（認証必須）
 *   GET /api/v1/teams/{id}/members — メンバー一覧取得（認証必須）
 *
 * 目的: 認証済みユーザーによるチーム情報参照の負荷耐性を計測する。
 *
 * 実行例:
 *   k6 run k6/scenarios/03_team.js
 *   k6 run --env SAMPLE_TEAM_ID=1 k6/scenarios/03_team.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { login, authGetHeaders } from '../lib/auth.js';
import { BASE_URL, SAMPLE_IDS } from '../config/env.local.js';
import { randomSleep } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '30s', target: 15 },
    { duration: '1m', target: 40 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

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

  // チーム詳細取得 GET /api/v1/teams/{id}
  const teamRes = http.get(
    `${BASE_URL}/api/v1/teams/${SAMPLE_IDS.teamId}`,
    {
      ...headers,
      tags: { name: 'team_detail' },
    }
  );
  check(teamRes, {
    'team detail: status 200 or 404': (r) => r.status === 200 || r.status === 404,
    'team detail: response time < 1000ms': (r) => r.timings.duration < 1000,
  });

  randomSleep(0.5, 1.0);

  // チームメンバー一覧 GET /api/v1/teams/{id}/members
  const membersRes = http.get(
    `${BASE_URL}/api/v1/teams/${SAMPLE_IDS.teamId}/members`,
    {
      ...headers,
      tags: { name: 'team_members_list' },
    }
  );
  check(membersRes, {
    'team members: status 200 or 403 or 404': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
    'team members: response time < 1500ms': (r) => r.timings.duration < 1500,
  });

  randomSleep(0.5, 1.5);
}
