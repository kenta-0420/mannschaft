package com.mannschaft.app.payment.spi;

/** 課金ゲート対象の実体と所属scopeを表す読み取り専用記述。 */
public record ContentGateTarget(Long contentId, Long teamId, Long organizationId) {
    public boolean hasExactlyOneScope() {
        return (teamId != null) ^ (organizationId != null);
    }

    /** 個人コンテンツ（team/orgのいずれも持たない）を表す。 */
    public boolean isPersonal() {
        return teamId == null && organizationId == null;
    }
}
