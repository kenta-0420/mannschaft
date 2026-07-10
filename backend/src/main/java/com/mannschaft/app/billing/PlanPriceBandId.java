package com.mannschaft.app.billing;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * {@link PlanPriceBandEntity} の複合主キー（plan_key × scope_kind × band_no・設計書 01 §2.4）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlanPriceBandId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String planKey;
    private String scopeKind;
    private Short bandNo;
}
