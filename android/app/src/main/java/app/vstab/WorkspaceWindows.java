package app.vstab;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small, bounded registry. A slot gives each remote window its own cookie host. */
final class WorkspaceWindows {
    static final int LIMIT = 4;
    private final Map<String, Integer> slots = new LinkedHashMap<>();

    synchronized int acquire(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid workspace window identity.");
        Integer existing = slots.get(id);
        if (existing != null) return existing;
        for (int slot = 0; slot < LIMIT; slot++) if (!slots.containsValue(slot)) {
            slots.put(id, slot); return slot;
        }
        throw new IllegalStateException("Four workspace windows are already open. Close one before opening another.");
    }

    synchronized void release(String id) { slots.remove(id); }
    synchronized int count() { return slots.size(); }
    static String loopbackHost(int slot) {
        if (slot < 0 || slot >= LIMIT) throw new IllegalArgumentException("Invalid workspace slot.");
        return "127.0.0." + (slot + 1);
    }
    static boolean isWorkbenchHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "127.0.0.2".equals(host)
                || "127.0.0.3".equals(host) || "127.0.0.4".equals(host);
    }
}
