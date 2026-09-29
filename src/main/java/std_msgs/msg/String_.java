package std_msgs.msg;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * ROS 2 std_msgs/msg/String message.
 */
public record String_(String data) {
    public static final TypeSupport<String_> TYPE_SUPPORT = Ros2TypeSupport.of(String_.class);
}
