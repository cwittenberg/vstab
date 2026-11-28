package app.vstab;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AndroidDnsResolverTest {
    private static byte[] question(int type) {
        return new byte[] {0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                1, 'a', 0, (byte) (type >>> 8), (byte) type, 0, 1};
    }
    private static byte[] response(byte[] question, int rcode) {
        byte[] result = question.clone();
        result[0] = 0x56; result[1] = 0x78;
        result[2] = (byte) 0x81; result[3] = (byte) (0x80 | rcode);
        return result;
    }

    @Test public void forwardsAllTypesAndPreservesPacketAndCallerTransactionId() throws Exception {
        for (int type : new int[] {1, 28, 16, 65, 65280}) {
            byte[] query = question(type);
            byte[] original = query.clone();
            byte[] platformAnswer = response(query, 0);
            AtomicBoolean cancelled = new AtomicBoolean();
            try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
                assertArrayEquals(original, packet);
                packet[0] = 99;
                callback.answer(platformAnswer);
                return () -> cancelled.set(true);
            }, 1000)) {
                byte[] actual = resolver.query(query);
                byte[] expected = platformAnswer.clone();
                expected[0] = original[0]; expected[1] = original[1];
                assertArrayEquals(expected, actual);
                assertArrayEquals(original, query);
                assertEquals(0x56, platformAnswer[0]);
                assertTrue(cancelled.get());
            }
        }
    }

    @Test public void preservesNxDomainAndLargeWireAnswers() throws Exception {
        byte[] expected = Arrays.copyOf(response(question(28), 3), 65535);
        try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
            callback.answer(expected); return () -> { };
        }, 1000)) {
            byte[] actual = resolver.query(question(28));
            assertEquals(65535, actual.length);
            assertEquals(3, actual[3] & 15);
        }
    }

    @Test public void rejectsUnboundedOrShortRequestsBeforeCallingAndroid() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
            called.set(true); return () -> { };
        }, 1000)) {
            for (byte[] packet : new byte[][] {null, new byte[11], new byte[65536]}) {
                assertThrows(IllegalArgumentException.class, () -> resolver.query(packet));
            }
            assertFalse(called.get());
        }
    }

    @Test public void rejectsMalformedAndroidResponses() throws Exception {
        for (byte[] bad : new byte[][] {null, new byte[11], new byte[65536]}) {
            try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
                callback.answer(bad); return () -> { };
            }, 1000)) {
                assertThrows(IOException.class, () -> resolver.query(question(1)));
            }
        }
    }

    @Test public void timeoutCancelsTheActualOperation() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) ->
                () -> cancelled.set(true), 20)) {
            assertThrows(AndroidDnsResolver.QueryTimeoutException.class,
                    () -> resolver.query(question(1)));
            assertTrue(cancelled.get());
        }
    }

    @Test public void platformFailureReachesCallerAndCancels() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        IOException expected = new IOException("fixture resolver failure");
        try (AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
            callback.failure(expected); return () -> cancelled.set(true);
        }, 1000)) {
            assertSame(expected, assertThrows(IOException.class, () -> resolver.query(question(1))));
            assertTrue(cancelled.get());
        }
    }

    @Test public void closingCancelsAnActiveQueryAndRejectsNewQueries() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
            started.countDown(); return () -> cancelled.set(true);
        }, 30000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<IOException> result = worker.submit(() ->
                    assertThrows(IOException.class, () -> resolver.query(question(1))));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            resolver.close();
            assertNotNull(result.get(1, TimeUnit.SECONDS));
            assertTrue(cancelled.get());
            assertThrows(IOException.class, () -> resolver.query(question(1)));
        } finally { resolver.close(); worker.shutdownNow(); }
    }

    @Test public void closeRacingWithTransportAttachmentStillCancels() throws Exception {
        CountDownLatch started = new CountDownLatch(1), attach = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AndroidDnsResolver resolver = new AndroidDnsResolver((packet, callback) -> {
            started.countDown();
            try { if (!attach.await(1, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
            catch (InterruptedException error) { throw new IOException(error); }
            return () -> cancelled.set(true);
        }, 30000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<IOException> result = worker.submit(() ->
                    assertThrows(IOException.class, () -> resolver.query(question(1))));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            resolver.close();
            attach.countDown();
            assertNotNull(result.get(1, TimeUnit.SECONDS));
            assertTrue(cancelled.get());
        } finally { attach.countDown(); resolver.close(); worker.shutdownNow(); }
    }
}
