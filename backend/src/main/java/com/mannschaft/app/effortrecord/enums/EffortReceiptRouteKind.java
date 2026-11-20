package com.mannschaft.app.effortrecord.enums;

/** F06.4.1 冪等 receipt のルーティング種別。OWNER=本人 user、TEAM=team（team_id 基準。組織には依存しない）。 */
public enum EffortReceiptRouteKind {
    OWNER,
    TEAM
}
