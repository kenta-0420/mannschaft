package com.mannschaft.app.billing;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * {@link PlanFeatureEntity} の複合主キー（plan_key × feature_key・設計書 01 §2.3）。
 *
 * <p>プラン→機能の展開表であり、サロゲートキー不要（マスタ例外・自然キー複合）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlanFeatureId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String planKey;
    private String featureKey;
}
