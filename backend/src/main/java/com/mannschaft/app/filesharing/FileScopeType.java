package com.mannschaft.app.filesharing;

/**
 * ファイル共有のスコープ種別。フォルダが属するスコープを表す。
 *
 * <p>F08.7.1 / 04 リーグ単位ファイル置き場で {@link #TOURNAMENT} / {@link #TOURNAMENT_DIVISION} を追加した。
 * これらのスコープでは {@code shared_folders.organization_id} に主催組織 ID、
 * {@code shared_folders.scope_ref_id} に大会 ID / ディビジョン ID を保持する（設計書 §2.1）。
 * クォータ計量は主催組織に集約するため、{@link com.mannschaft.app.common.storage.quota.StorageScopeType}
 * には新値を追加せず {@code StorageScopeType.ORGANIZATION} に丸める（設計書 §6）。</p>
 */
public enum FileScopeType {
    TEAM,
    ORGANIZATION,
    PERSONAL,
    /** F08.7.1: 大会全体スコープ。organization_id=主催組織 / scope_ref_id=tournaments.id。 */
    TOURNAMENT,
    /** F08.7.1: ディビジョンスコープ。organization_id=主催組織 / scope_ref_id=tournament_divisions.id。 */
    TOURNAMENT_DIVISION
}
