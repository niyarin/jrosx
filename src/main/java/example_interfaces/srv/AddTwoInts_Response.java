package example_interfaces.srv;

import ddsj.dds.core.TypeSupport;
import jrosx.Ros2TypeSupport;

/**
 * Response message for example_interfaces/srv/AddTwoInts service.
 * ROS2 service correlation (client GUID + sequence number) is handled via RTPS Inline QoS,
 * not embedded in the CDR payload.
 */
public record AddTwoInts_Response(long sum) {
    public static final TypeSupport<AddTwoInts_Response> TYPE_SUPPORT =
            Ros2TypeSupport.forService(AddTwoInts_Response.class, "example_interfaces/srv/AddTwoInts_Response");
}
