package example_interfaces.srv;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Request message for example_interfaces/srv/AddTwoInts service.
 * ROS2 service correlation (client GUID + sequence number) is handled via RTPS Inline QoS,
 * not embedded in the CDR payload.
 */
public record AddTwoInts_Request(long a, long b) {
    public static final TypeSupport<AddTwoInts_Request> TYPE_SUPPORT =
            Ros2TypeSupport.forService(AddTwoInts_Request.class, "example_interfaces/srv/AddTwoInts_Request");
}
