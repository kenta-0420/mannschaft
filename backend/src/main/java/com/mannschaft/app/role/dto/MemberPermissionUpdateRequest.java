package com.mannschaft.app.role.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** MEMBER 既定3権限の完全置換要求。 */
public record MemberPermissionUpdateRequest(
        @NotNull @Size(min = 3, max = 3) List<@Valid MemberPermissionUpdateItem> permissions) {
}
