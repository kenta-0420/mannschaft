package com.mannschaft.app.resident.transitivetx;

import com.mannschaft.app.membership.transitivetx.MembershipBridge;

import org.springframework.transaction.annotation.Transactional;

/** クラスレベル {@code @Transactional} 用 fixture。 */
@Transactional
public class ClassTransactionalFixture {

    public void execute() {
        new MembershipBridge().forward();
    }
}
