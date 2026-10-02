package jrosx;

import java.util.ArrayList;
import java.util.List;

/** Owns partially constructed endpoints as well as fully initialized actions. */
final class ActionResources implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    <T extends AutoCloseable> T add(T resource) { resources.add(resource); return resource; }
    public void close() {
        RuntimeException failure = null;
        for (var resource : resources.reversed()) {
            try { resource.close(); }
            catch (Exception e) {
                if (failure == null) failure = new IllegalStateException("Failed to close action resources", e);
                else failure.addSuppressed(e);
            }
        }
        resources.clear();
        if (failure != null) throw failure;
    }
}
