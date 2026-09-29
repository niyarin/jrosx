package example_interfaces.srv;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Simple request without header - for debugging type matching.
 */
public record SimpleRequest(long a, long b) {
    public static final TypeSupport<SimpleRequest> TYPE_SUPPORT =
            Ros2TypeSupport.of(SimpleRequest.class, "example_interfaces/srv/AddTwoInts_Request");
}
