package com.mannschaft.app.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** プレビューした全対象のversionと明示的な反映項目。空リストは予定のみ保存。 */
public record ActivitySyncConfirmation(@NotNull @Valid ExpectedScheduleState expectedScheduleState,
                                       @NotNull @Valid List<@NotNull ActivitySyncSelection> activities) {
}
