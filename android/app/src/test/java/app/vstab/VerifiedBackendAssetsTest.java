package app.vstab;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VerifiedBackendAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] ORIGINAL = "export const pinned = true;".getBytes(StandardCharsets.UTF_8);

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) output.append(String.format("%02x", value & 255));
        return output.toString();
    }

    private VerifiedBackendAssets assets(File directory, VerifiedBackendAssets.Fetcher fetcher) throws Exception {
        return new VerifiedBackendAssets(directory, Collections.singletonMap("static/out/pinned.js",
                new VerifiedBackendAssets.Entry(sha(ORIGINAL), ORIGINAL.length, "/stable-commit/static/out/pinned.js")), fetcher);
    }

    @Test public void validBytesAreCachedByHashAndReused() throws Exception {
        AtomicInteger fetched = new AtomicInteger();
        File cache = temporary.newFolder();
        VerifiedBackendAssets assets = assets(cache, path -> { fetched.incrementAndGet(); return new ByteArrayInputStream(ORIGINAL); });
        try (InputStream input = assets.open("static/out/pinned.js")) { assertArrayEquals(ORIGINAL, input.readAllBytes()); }
        try (InputStream input = assets.open("static/out/pinned.js")) { assertArrayEquals(ORIGINAL, input.readAllBytes()); }
        assertEquals(1, fetched.get());
        assertTrue(new File(cache, sha(ORIGINAL) + ".asset").isFile());
    }

    @Test public void cacheCorruptionIsDetectedBeforeServingAndRefetched() throws Exception {
        AtomicInteger fetched = new AtomicInteger();
        File cache = temporary.newFolder();
        VerifiedBackendAssets assets = assets(cache, path -> { fetched.incrementAndGet(); return new ByteArrayInputStream(ORIGINAL); });
        try (InputStream input = assets.open("static/out/pinned.js")) { assertArrayEquals(ORIGINAL, input.readAllBytes()); }
        Files.write(new File(cache, sha(ORIGINAL) + ".asset").toPath(), "corrupt cached script".getBytes(StandardCharsets.UTF_8));
        try (InputStream input = assets.open("static/out/pinned.js")) { assertArrayEquals(ORIGINAL, input.readAllBytes()); }
        assertEquals(2, fetched.get());
    }

    @Test public void sameLengthTamperingIsRejectedWithoutCommittingAnyCacheFile() throws Exception {
        File cache = temporary.newFolder();
        byte[] changed = ORIGINAL.clone(); changed[0] ^= 1;
        VerifiedBackendAssets assets = assets(cache, path -> new ByteArrayInputStream(changed));
        try { assets.open("static/out/pinned.js"); fail("Tampered script must not be served"); }
        catch (IOException rejected) { assertTrue(rejected.getMessage().contains("pinned APK bytes")); }
        assertEquals(0, cache.list().length);
    }

    @Test public void truncatedAndOversizedResponsesAreRejected() throws Exception {
        for (int length : new int[]{ORIGINAL.length - 1, ORIGINAL.length + 1}) {
            File cache = temporary.newFolder();
            VerifiedBackendAssets assets = assets(cache, path -> new ByteArrayInputStream(Arrays.copyOf(ORIGINAL, length)));
            try { assets.open("static/out/pinned.js"); fail("Different length must not be served"); }
            catch (IOException expected) { }
            assertEquals(0, cache.list().length);
        }
    }

    @Test public void unlistedPathsNeverReachTheBackend() throws Exception {
        AtomicInteger fetched = new AtomicInteger();
        VerifiedBackendAssets assets = assets(temporary.newFolder(), path -> { fetched.incrementAndGet(); return new ByteArrayInputStream(ORIGINAL); });
        try { assets.open("static/out/unlisted.js"); fail("Unlisted script must not be fetched"); }
        catch (IOException rejected) { assertTrue(rejected.getMessage().contains("not authorized")); }
        assertEquals(0, fetched.get());
    }

    @Test public void corruptCacheAndMissingBackendNeverReturnStaleBytes() throws Exception {
        File cache = temporary.newFolder();
        Files.write(new File(cache, sha(ORIGINAL) + ".asset").toPath(), "untrusted".getBytes(StandardCharsets.UTF_8));
        VerifiedBackendAssets assets = assets(cache, path -> { throw new IOException("Backend unavailable"); });
        try { assets.open("static/out/pinned.js"); fail("Missing backend must be a clear failure"); }
        catch (IOException rejected) { assertEquals("Backend unavailable", rejected.getMessage()); }
        assertEquals(0, cache.list().length);
    }

    @Test public void httpFetchUsesOnlyTheAuthenticatedOriginAndRejectsRedirects() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<String> request = new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread server = new Thread(() -> {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(5000);
                    ByteArrayOutputStream header = new ByteArrayOutputStream(); int state = 0;
                    while (state < 4) {
                        int value = client.getInputStream().read(); if (value < 0) throw new IOException("Incomplete request");
                        header.write(value);
                        if (value == (state == 0 || state == 2 ? '\r' : '\n')) state++; else state = value == '\r' ? 1 : 0;
                    }
                    request.set(header.toString("ISO-8859-1"));
                    client.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/unrelated\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (Throwable error) { failed.set(error); }
            });
            server.setDaemon(true); server.start();
            VerifiedBackendAssets.Fetcher source = VerifiedBackendAssets.authenticatedHttp("http://127.0.0.1:" + listener.getLocalPort(),
                    Arrays.asList("gateway=old; Path=/", "gateway=one; Path=/; HttpOnly", "session=two; SameSite=Strict"));
            try { source.open("/stable-commit/static/out/pinned.js"); fail("Redirects must not be followed"); }
            catch (IOException rejected) { assertTrue(rejected.getMessage().contains("matching backend")); }
            server.join(5000);
            assertNull(failed.get());
            assertNotNull(request.get());
            assertTrue(request.get().startsWith("GET /stable-commit/static/out/pinned.js HTTP/1.1\r\n"));
            assertTrue(request.get().contains("Cookie: gateway=one; session=two\r\n"));
            assertFalse(request.get().contains("gateway=old"));
            assertFalse(request.get().contains("HttpOnly"));
        }
    }
}
