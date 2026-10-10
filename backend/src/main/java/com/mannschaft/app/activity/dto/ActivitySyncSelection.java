package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 一件の活動に反映する基本フィールドを限定する選択。 */
public record ActivitySyncSelection(@NotNull @jakarta.validation.constraints.Positive Long id,
                                    @NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
                                    @NotNull List<String> applyFields) {
}
