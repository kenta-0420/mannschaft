package com.mannschaft.app.common.duplicatename;

/**
 * CMP-260901-1538 柱③-A: 同名確認フローの対象スコープ種別。
 *
 * <p>組織・チームのそれぞれで同名候補を扱うが、fingerprint（HMAC）の束縛対象を
 * スコープごとに分離するために使用する（組織向けに発行した fingerprint がチーム作成に
 * 流用できてしまうことを防ぐ）。</p>
 */
public enum DuplicateNameScopeKind {
    ORGANIZATION,
    TEAM
}
