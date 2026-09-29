package com.mannschaft.app.resident.transitivetx;

import com.mannschaft.app.common.transitivetx.CommonConstructorBridge;
import com.mannschaft.app.membership.transitivetx.MembershipBridge;
import com.mannschaft.app.proxy.repository.InheritedProxyRepository;
import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;
import com.mannschaft.app.resident.repository.TransitiveResidentRepository;

import org.springframework.transaction.annotation.Transactional;

/** D-3T メタテスト専用 fixture。 */
public class TransitiveTransactionalFixture {
    private final MembershipBridge membershipBridge = new MembershipBridge();
    private final TransitiveResidentRepository residentRepository = new TransitiveResidentRepository();
    private final InheritedProxyRepository inheritedProxyRepository = new InheritedProxyRepository();

    @Transactional
    public void execute() { membershipBridge.forward(); }

    @Transactional
    public void constructThroughCommon() {
        new CommonConstructorBridge(new TransitiveProxyRepository());
    }

    @Transactional
    public void cycle() { membershipBridge.cycleA(); membershipBridge.cycleB(); }

    @Transactional
    public void diamond() { membershipBridge.forward(); membershipBridge.secondPath(); }

    @Transactional(readOnly = true)
    public void readOnly() { membershipBridge.forward(); }

    @Transactional
    public void throughPrivateHelper() { privateHelper(); }

    private void privateHelper() { membershipBridge.forward(); }

    @Transactional
    public void safeOverload() { overloaded(1); }

    private void overloaded(int ignored) { residentRepository.save(); }

    @SuppressWarnings("unused")
    private void overloaded(String ignored) { membershipBridge.forward(); }

    @Transactional
    public void inheritedRepository() { inheritedProxyRepository.saveInherited(); }

    @Transactional
    public void empty() { }

    @Transactional
    public void unresolved(UnresolvedBridge bridge) { bridge.forward(); }

    @Transactional
    public void sameDomain() { residentRepository.save(); }

    public void notTransactional() { membershipBridge.forward(); }
}