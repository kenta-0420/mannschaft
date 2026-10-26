package com.mannschaft.app.diagnosis;

/** 本人診断セッションの状態。保留は途中状態をそのまま保持する。 */
public enum DiagnosisStatus { STARTED, TIE_BREAK_REQUIRED, COMPLETED, CANCELLED }
