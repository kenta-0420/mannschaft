package com.mannschaft.app.role.transitivetx;

import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;

/** membership から proxy Repository への二段目の委譲 fixture。 */
public class RoleBridge {

    private final TransitiveProxyRepository repository = new TransitiveProxyRepository();

    public void forward() {
        repository.save();
    }
}
