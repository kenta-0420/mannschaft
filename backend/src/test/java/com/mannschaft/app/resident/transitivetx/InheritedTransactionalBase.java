package com.mannschaft.app.resident.transitivetx;
import com.mannschaft.app.membership.transitivetx.MembershipBridge;
/** class-level Transactional fixture の継承元。 */
public class InheritedTransactionalBase {
    public void inheritedExecute() { new MembershipBridge().forward(); }
}