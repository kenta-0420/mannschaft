package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コルクボード詳細レスポンスDTO（カード・セクション含む）。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long ownerId;
    private final String name;
    private final String backgroundStyle;
    private final String editPolicy;
    private final Boolean isDefault;
    private final Long version;
    private final List<CorkboardCardResponse> cards;
    private final List<CorkboardGroupResponse> groups;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

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
}
