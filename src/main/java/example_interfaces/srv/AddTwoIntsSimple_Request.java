package example_interfaces.srv;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Simplified request without service header - for debugging.
 */
public record AddTwoIntsSimple_Request(long a, long b) {
    public static final TypeSupport<AddTwoIntsSimple_Request> TYPE_SUPPORT =
            Ros2TypeSupport.of(AddTwoIntsSimple_Request.class, "example_interfaces/srv/AddTwoInts_Request");
}
