package com.mannschaft.app.organization.dto;

/**
 * 組織の公開ページ・組織シェルで「チームとして加盟を申請」ボタンを出すかの判定（F01.2.1 §10.3・M3）。
 *
 * <p>{@code canApply} は「組織が見えて・受付中で・閲覧者が加盟操作権限を持つチームが1つ以上ある」ときだけ true。
 * 組織が存在しない・見えない・アーカイブ済み・受付 off・権限のあるチームが無い、のいずれでも同じ false を返し、
 * どの理由で false かを区別しない（存在オラクルにしない）。</p>
 */
public record OrgAffiliationEligibilityResponse(boolean canApply) {

    private static final OrgAffiliationEligibilityResponse NO = new OrgAffiliationEligibilityResponse(false);

    /** 申請できない（理由を区別しない同一の応答）。 */
    public static OrgAffiliationEligibilityResponse no() {
        return NO;
    }
}
