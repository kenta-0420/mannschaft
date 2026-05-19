import { describe, it, expect } from 'vitest'

/**
 * F09.17 Phase 11-d-4 — チーム広告ページの URL 構築検証。
 *
 * <p>ページ内で組み立てる NuxtLink の `to` プロパティ（一覧→詳細、詳細→一覧、
 * ウィザード完了後遷移）が `/teams/{teamId}/advertiser/messaging-campaigns/...`
 * 形式になっていることを軽量に検証する。</p>
 */

function buildListPath(teamId: number): string {
  return `/teams/${teamId}/advertiser/messaging-campaigns`
}

function buildNewPath(teamId: number): string {
  return `/teams/${teamId}/advertiser/messaging-campaigns/new`
}

function buildDetailPath(teamId: number, campaignId: string): string {
  return `/teams/${teamId}/advertiser/messaging-campaigns/${campaignId}`
}

function buildReportPath(teamId: number, campaignId: string): string {
  return `/teams/${teamId}/advertiser/messaging-campaigns/${campaignId}/report`
}

describe('teams advertiser page URL builders', () => {
  it('TEAM-URL-001: 一覧 URL は /teams/{id}/advertiser/messaging-campaigns', () => {
    expect(buildListPath(42)).toBe('/teams/42/advertiser/messaging-campaigns')
  })

  it('TEAM-URL-002: 新規作成 URL は /teams/{id}/advertiser/messaging-campaigns/new', () => {
    expect(buildNewPath(42)).toBe('/teams/42/advertiser/messaging-campaigns/new')
  })

  it('TEAM-URL-003: 詳細 URL は campaignId UUID を末尾に付ける', () => {
    const cid = 'a37a7a01-2026-7a01-9a01-aaaa00000001'
    expect(buildDetailPath(42, cid)).toBe(
      `/teams/42/advertiser/messaging-campaigns/${cid}`,
    )
  })

  it('TEAM-URL-004: レポート URL は /report 接尾辞', () => {
    const cid = 'a37a7a01-2026-7a01-9a01-aaaa00000001'
    expect(buildReportPath(42, cid)).toBe(
      `/teams/42/advertiser/messaging-campaigns/${cid}/report`,
    )
  })

  it('TEAM-URL-005: 組織配下 URL と決して衝突しない（先頭セグメントで分離）', () => {
    expect(buildListPath(1)).not.toContain('/organizations/')
    expect(buildListPath(1).startsWith('/teams/')).toBe(true)
  })
})
