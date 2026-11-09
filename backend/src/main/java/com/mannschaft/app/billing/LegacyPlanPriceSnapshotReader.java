package com.mannschaft.app.billing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * CMP-260930-1931の先行試練用最小骨格。実価格logic/SQL/selector接続は未実装。
 * 2入口は別proxyから呼ばれ、既存global routingを変えずPRIMARYの新write属性TXでSELECTする契約。
 */
@Service
public class LegacyPlanPriceSnapshotReader {

    /** 非削除SCHEDULEDの半開期間に合う要求pairだけ。公開履歴flagを返さない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Set<BillingPriceSelector.PlanScopeKey> readDueKeys(
            Set<BillingPriceSelector.PlanScopeKey> keys, Instant at) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }

    /**
     * 同PRIMARY TXで全ACTIVEとbounded公開履歴markerを観測し、親子を検証後scalarへcopyする契約。
     * 全要求keyへ三状態を返し、価格の未公開と公開済み販売不能を混同しない。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> readActive(
            Set<BillingPriceSelector.PlanScopeKey> keys, Instant at) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }
}
