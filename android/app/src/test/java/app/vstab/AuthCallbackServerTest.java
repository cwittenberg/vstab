package app.vstab;

import static org.junit.Assert.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AuthCallbackServerTest {
    @Test public void browserCallbackNeedsTheExactReceiverCapability() throws Exception {
        try (AuthCallbackServer server = new AuthCallbackServer()) {
            AtomicReference<String> received = new AtomicReference<>();
            server.receive(received::set);
            String callback = server.url() + "?vscode-reqid=random&code=percent%252F";
            HttpURLConnection request = (HttpURLConnection) new URL(callback).openConnection();
            assertEquals(200, request.getResponseCode());
            assertEquals("no-store", request.getHeaderField("Cache-Control"));
            assertTrue(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("VStab"));
            assertEquals(callback, received.get());
            received.set(null);
            request.disconnect();
            URL valid = new URL(server.url());
            HttpURLConnection foreign = (HttpURLConnection) new URL("http://127.0.0.1:" + valid.getPort() + "/wrong/callback?vscode-reqid=random").openConnection();
            assertEquals(404, foreign.getResponseCode());
            assertNull(received.get());
            foreign.disconnect();
        }
    }
}
