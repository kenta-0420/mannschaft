package com.mannschaft.app.membership.transitivetx;

import com.mannschaft.app.role.transitivetx.RoleBridge;

/** resident から role への一段目の委譲 fixture。 */
public class MembershipBridge {
    private final RoleBridge roleBridge = new RoleBridge();
    public void forward() { roleBridge.forward(); }
    public void cycleA() { cycleB(); }
    public void cycleB() { cycleA(); roleBridge.forward(); }
    public void secondPath() { roleBridge.forward(); }
}