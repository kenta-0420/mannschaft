/**
 * シナリオ 04: 未ログイン公開ページ（F19.1 / F15.4）
 *
 * 対象エンドポイント（SecurityConfig にて認証不要 permitAll 設定済み）:
 *   GET /api/v1/public/teams/{id}
 *     - 公開チーム詳細（F15.4 Phase 5）
 *     - PUBLIC かつ未 archive かつ未削除のチームのみ 200、それ以外 404（IDOR 対策）
 *   GET /api/v1/public/organizations/{id}
 *     - 公開組織詳細（F19.1 Phase 1）
 *   GET /api/v1/public/teams/{id}/posts
 *     - チーム公開投稿一覧
 *   GET /api/v1/public/organizations/{id}/posts
 *     - 組織公開投稿一覧
 *
 * レート制限:
 *   PublicApiRateLimitFilter: 未ログイン 60 req/min/IP
 *   スパイク試験では limit を超えないよう stages を調整すること
 *
 * 目的: 未ログイン公開ページのスループット・レスポンスタイムを計測する。
 *   外部流入（SNS 拡散時など）の大量アクセスを想定。
 *
 * 実行例:
 *   k6 run k6/scenarios/04_public_pages.js
 *   k6 run --env SAMPLE_TEAM_ID=1 --env SAMPLE_ORG_ID=1 k6/scenarios/04_public_pages.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, SAMPLE_IDS } from '../config/env.local.js';
import { randomSleep } from '../lib/helpers.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m', target: 50 },   // 公開ページは高トラフィック想定（レートリミット未満）
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],  // 公開ページは 1 秒以内目標
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // 公開チーム詳細 GET /api/v1/public/teams/{id}
  const pubTeamRes = http.get(
    `${BASE_URL}/api/v1/public/teams/${SAMPLE_IDS.teamId}`,
    {
      headers: { Accept: 'application/json' },
      tags: { name: 'public_team_detail' },
    }
  );
  check(pubTeamRes, {
    'public team: status 200 or 404': (r) => r.status === 200 || r.status === 404,
    'public team: not 500': (r) => r.status !== 500,
    'public team: response time < 1000ms': (r) => r.timings.duration < 1000,
  });

  randomSleep(0.5, 1.0);

  // 公開組織詳細 GET /api/v1/public/organizations/{id}
  const pubOrgRes = http.get(
    `${BASE_URL}/api/v1/public/organizations/${SAMPLE_IDS.orgId}`,
    {
      headers: { Accept: 'application/json' },
      tags: { name: 'public_org_detail' },
    }
  );
  check(pubOrgRes, {
    'public org: status 200 or 404': (r) => r.status === 200 || r.status === 404,
    'public org: not 500': (r) => r.status !== 500,
    'public org: response time < 1000ms': (r) => r.timings.duration < 1000,
  });

  randomSleep(0.5, 1.0);

  // 公開チーム投稿一覧 GET /api/v1/public/teams/{id}/posts
  const pubTeamPostsRes = http.get(
    `${BASE_URL}/api/v1/public/teams/${SAMPLE_IDS.teamId}/posts`,
    {
      headers: { Accept: 'application/json' },
      tags: { name: 'public_team_posts' },
    }
  );
  check(pubTeamPostsRes, {
    'public team posts: status 200 or 404': (r) => r.status === 200 || r.status === 404,
    'public team posts: not 500': (r) => r.status !== 500,
  });

  randomSleep(0.5, 1.5);
}
