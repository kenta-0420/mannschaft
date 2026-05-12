package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * チームメンバーからエントリー表を一括ロードするリクエストDTO。
 *
 * <p>F08.7 Phase 9: userIds=null の場合は全アクティブメンバーをロードする。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadFromTeamRequest {

    /**
     * ロード対象のユーザーIDリスト。
     * null の場合は全アクティブチームメンバーをロードする。
     */
    List<Long> userIds;

    /**
     * 既存エントリーを上書きするかどうか。
     * false（デフォルト）の場合は既存エントリー済みユーザーはスキップする。
     */
    @Builder.Default
    boolean overwriteExisting = false;
}
