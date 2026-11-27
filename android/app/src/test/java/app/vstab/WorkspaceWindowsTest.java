package app.vstab;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkspaceWindowsTest {
    @Test public void aNormalLaunchAllocatesOnlyOneWindowAndRecreationReusesIt() {
        WorkspaceWindows registry = new WorkspaceWindows();
        assertEquals(0, registry.count());
        assertEquals(0, registry.acquire("main"));
        assertEquals(0, registry.acquire("main"));
        assertEquals(1, registry.count());
    }
    @Test public void windowsHaveSeparateCookieHostsAndClosingOneKeepsOthers() {
        WorkspaceWindows registry = new WorkspaceWindows();
        int left = registry.acquire("left"), right = registry.acquire("right");
        assertNotEquals(WorkspaceWindows.loopbackHost(left), WorkspaceWindows.loopbackHost(right));
        registry.release("left");
        assertEquals(right, registry.acquire("right"));
        assertEquals(left, registry.acquire("third"));
    }
    @Test public void limitIsExplicitAndDoesNotEvictWorkingWindows() {
        WorkspaceWindows registry = new WorkspaceWindows();
        for (int i = 0; i < WorkspaceWindows.LIMIT; i++) registry.acquire("window-" + i);
        assertThrows(IllegalStateException.class, () -> registry.acquire("overflow"));
        assertEquals(WorkspaceWindows.LIMIT, registry.count());
        assertEquals(1, registry.acquire("window-1"));
    }
    @Test public void invalidIdentitiesAndNonLoopbackHostsAreRejected() {
        WorkspaceWindows registry = new WorkspaceWindows();
        assertThrows(IllegalArgumentException.class, () -> registry.acquire("../../other"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceWindows.loopbackHost(4));
        assertFalse(WorkspaceWindows.isWorkbenchHost("127.0.0.1.evil.test"));
        assertFalse(WorkspaceWindows.isWorkbenchHost("192.168.0.1"));
        assertTrue(WorkspaceWindows.isWorkbenchHost("127.0.0.4"));
    }
}
