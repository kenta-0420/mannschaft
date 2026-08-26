package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** スコープ別メンバー表示色の上書き。 */
public record UpdateMemberCalendarColorRequest(
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String calendarColor
) { }
