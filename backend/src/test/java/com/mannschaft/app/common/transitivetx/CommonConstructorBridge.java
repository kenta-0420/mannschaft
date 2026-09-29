package com.mannschaft.app.common.transitivetx;

import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;

/** common と constructor call を跨いでも探索を継続する fixture。 */
public class CommonConstructorBridge {

    public CommonConstructorBridge() {
        new TransitiveProxyRepository().save();
    }
}
