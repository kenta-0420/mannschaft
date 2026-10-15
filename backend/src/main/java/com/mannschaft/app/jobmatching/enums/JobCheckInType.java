package com.mannschaft.app.jobmatching.enums;

/**
 * QR チェックイン／アウトの種別。F13.1 Phase 13.1.2。
 *
 * <p>同一契約あたり {@link #IN} / {@link #OUT} は各 1 件まで
 * （{@code job_check_ins} の {@code uq_jci_contract_type} で保証）。</p>
 */
public enum JobCheckInType {

    /** チェックイン（業務開始時）。CHECKED_IN 遷移のトリガー。 */
    IN,

    /** チェックアウト（業務終了時）。CHECKED_OUT 遷移のトリガー。 */
    OUT
}
