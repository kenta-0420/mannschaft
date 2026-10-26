package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先ターゲット1件（軍議第8版確定稿 §3.3）。
 *
 * <p>{@code type} が {@code ORGANIZATION} なら {@code id} は組織ID、
 * {@code TEAM} ならチームID。</p>
 */
@Getter
@NoArgsConstructor
public class ConfirmableTargetSpec {

    @NotNull
    private ConfirmableTargetType type;

    @NotNull
    private Long id;

    public ConfirmableTargetSpec(ConfirmableTargetType type, Long id) {
        this.type = type;
        this.id = id;
    }
}
