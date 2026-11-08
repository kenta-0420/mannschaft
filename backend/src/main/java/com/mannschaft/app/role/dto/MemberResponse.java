package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メンバー情報レスポンス。
 *
 * <p>{@code permissionGroups} は管理者（DEPUTY_ADMIN 以上）向けのメンバー一覧だけに載せる
 * （F01.2 02_api_design.md の返却フィールドのロール別制限）。一般メンバー・非メンバー向けは null で、JSON にも出さない。</p>
 */
@Getter
@Schema(name = "ScopeMemberResponse")
public class MemberResponse {

    private final Long userId;
    private final String displayName;
    private final String avatarUrl;
    private final String roleName;
    private final LocalDateTime joinedAt;
    private final String calendarColor;

    /**
     * 割り当て済みの権限グループ（管理者向けのみ。DEPUTY_ADMIN・MEMBER 以外、未割当は空配列。それ以外の閲覧者向けは null）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<PermissionGroupRef> permissionGroups;

    public MemberResponse(Long userId, String displayName, String avatarUrl,
                          String roleName, LocalDateTime joinedAt) {
        this(userId, displayName, avatarUrl, roleName, joinedAt, null);
    }

    public MemberResponse(Long userId, String displayName, String avatarUrl,
                          String roleName, LocalDateTime joinedAt, String calendarColor) {
        this(userId, displayName, avatarUrl, roleName, joinedAt, calendarColor, null);
    }

    private MemberResponse(Long userId, String displayName, String avatarUrl,
                           String roleName, LocalDateTime joinedAt, String calendarColor,
                           List<PermissionGroupRef> permissionGroups) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.roleName = roleName;
        this.joinedAt = joinedAt;
        this.calendarColor = calendarColor;
        this.permissionGroups = permissionGroups;
    }

    /** 権限グループの割当を載せた複製を返す（管理者向けの一覧でだけ使う）。 */
    public MemberResponse withPermissionGroups(List<PermissionGroupRef> groups) {
        return new MemberResponse(userId, displayName, avatarUrl, roleName, joinedAt, calendarColor, groups);
    }

    /**
     * メンバーに割り当て済みの権限グループの最小情報。
     *
     * @param id   権限グループ ID
     * @param name 権限グループ名
     */
    @Schema(name = "MemberPermissionGroupRef")
    public record PermissionGroupRef(Long id, String name) {
    }
}
