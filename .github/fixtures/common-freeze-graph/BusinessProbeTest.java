package fixture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BusinessProbeTest {
    @Test void businessExecutionMarker() throws Exception {
        Files.writeString(Path.of(System.getProperty("fixture.dir"), "business-ran"), "executed\n");
    }
}
