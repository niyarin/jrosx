package std_msgs.msg;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Test: Two longs in msg namespace.
 */
public record TwoLongs_(long a, long b) {
    // Use explicit type name to match example_interfaces naming
    public static final TypeSupport<TwoLongs_> TYPE_SUPPORT =
            Ros2TypeSupport.of(TwoLongs_.class);
}
