package com.mannschaft.app.gdpr.dto;

import java.util.List;
import java.util.Map;

/**
 * GDPR データエクスポートで、<b>エクスポートのトランザクションに入る前に</b>他ドメインから取得しておく個人データ。
 *
 * <p>{@code DataExportService#processExportAsync} は gdpr ドメインのトランザクションで動く。そこから他ドメインの
 * Repository へ届くと、トランザクションがドメインをまたぐ（CLAUDE.md 原則5・D-3T）。新しく収集対象に加えるカテゴリは、
 * 呼び出し元（{@code GdprController}。トランザクションの外）が {@code PersonalDataPrefetcher} で、各ドメインの
 * 読み取りトランザクションで先に取得し、本レコードで渡す（{@link UserEmailInfo} と同じ設計）。</p>
 *
 * @param teamOrgMemberships {@code memberships} カテゴリ（F01.2.1 §15・AC-G127。team_org_memberships の
 *                           invited_by・responded_by として関わった加盟）。カテゴリが収集対象でなければ {@code null}
 */
public record PrefetchedPersonalData(List<Map<String, Object>> teamOrgMemberships) {

    /** 何も事前取得していない（事前取得の要るカテゴリは収集できない）。 */
    public static PrefetchedPersonalData none() {
        return new PrefetchedPersonalData(null);
    }
}
