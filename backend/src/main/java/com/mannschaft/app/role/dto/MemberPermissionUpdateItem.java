package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** MEMBER 既定権限更新要求の1件。 */
public record MemberPermissionUpdateItem(
        @NotBlank String name,
        @NotNull Boolean enabled) {
}
