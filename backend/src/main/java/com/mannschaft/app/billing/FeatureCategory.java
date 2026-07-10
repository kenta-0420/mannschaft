package com.mannschaft.app.billing;

/**
 * F20.1: {@code feature_catalog.category} の区分（VARCHAR(8) + CHECK）。
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2.1。</p>
 */
public enum FeatureCategory {
    /** 内向き機能。無料枠を広く取る方針（非営利無料枠の対象になりうる）。 */
    INTERNAL,
    /** 収益機能。スコープの区分（営利/非営利）を問わず常に有料。 */
    REVENUE
}
