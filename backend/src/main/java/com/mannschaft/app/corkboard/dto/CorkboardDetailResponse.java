package com.mannschaft.app.corkboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コルクボード詳細レスポンスDTO（カード・セクション含む）。
 */
@Builder(toBuilder = true)
@Getter
public class CorkboardDetailResponse {

    private final Long id;
    private final BoardScopeDto scope;
    private final Long ownerId;
    private final String name;
    private final BoardSettingsDto settings;
    private final Long version;
    private final BoardContentDto boardContent;

    /**
     * F09.8 件A: 現在の閲覧ユーザーがこのボードを編集可能か。
     *
     * <p>BE の {@code CorkboardPermissionService#checkEditPermission} と同じロジックで判定する。
     * フロントの「編集ボタン disabled」表示用に、403 を投げずに boolean で返す配線。</p>
     *
     * <ul>
     *   <li>{@code PERSONAL} &rarr; 所有者のみ {@code true}</li>
     *   <li>共有 ({@code TEAM} / {@code ORGANIZATION}) かつ {@code edit_policy = ADMIN_ONLY}
     *       &rarr; ADMIN/DEPUTY_ADMIN のみ {@code true}</li>
     *   <li>共有かつ {@code edit_policy = ALL_MEMBERS} &rarr; メンバー全員 {@code true}</li>
     * </ul>
     */
    private final Boolean viewerCanEdit;

    public record BoardScopeDto(String scopeType, Long scopeId) {}

    public record BoardSettingsDto(String backgroundStyle, String editPolicy, Boolean isDefault) {}

    public record BoardContentDto(List<CorkboardCardResponse> cards, List<CorkboardGroupResponse> groups,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
