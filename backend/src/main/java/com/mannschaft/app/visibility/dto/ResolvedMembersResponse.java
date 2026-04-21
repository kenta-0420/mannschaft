package com.mannschaft.app.visibility.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 解決済みメンバー一覧レスポンス DTO。
 *
 * <p>テンプレートに基づいて解決された閲覧可能ユーザーIDの一覧を返す。
 * プレビュー用途であり、実際の権限判定には {@code /evaluate} エンドポイントを使用すること。</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ResolvedMembersResponse {

    /** 対象テンプレートID */
    private Long templateId;

    /** 解決されたユーザーの総数 */
    private int totalUsers;

    /** 対象ユーザーIDの一覧（プレビュー用） */
    private Set<Long> userIds;

    /** 解決実行日時 */
    private LocalDateTime resolvedAt;
}
