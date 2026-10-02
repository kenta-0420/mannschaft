package com.mannschaft.app.common.featuregate;

/** 常時到達を認める API の業務上の区分。 */
public enum AlwaysReachableCategory {
    CORE,
    PUBLIC_LIFELINE,
    GATE_CONTROL_PLANE,
    PLATFORM_INFRA
}
