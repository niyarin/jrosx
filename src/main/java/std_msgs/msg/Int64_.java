package std_msgs.msg;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * ROS 2 std_msgs/msg/Int64 message.
 */
public record Int64_(long data) {
    public static final TypeSupport<Int64_> TYPE_SUPPORT = Ros2TypeSupport.of(Int64_.class);
}
