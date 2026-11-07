package external.transitivetx;

import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.Port;
import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;

/** 明示importされても本番アプリ外の実装を候補数へ含めないための検体。 */
public class ExternalPortBridge implements Port {
    @Override
    public void forward(int value) { new TransitiveProxyRepository().save(); }
}