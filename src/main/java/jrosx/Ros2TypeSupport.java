package jrosx;

import ddsj.dds.core.TypeSupport;

/**
 * Creates CDR-compatible TypeSupport for Java records with ROS2 type naming.
 *
 * <p>The Java package and class name are converted to DDS type name format:
 * <pre>
 * geometry_msgs.msg.Point → geometry_msgs::msg::dds_::Point_
 * std_msgs.msg.String     → std_msgs::msg::dds_::String_
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * package geometry_msgs.msg;
 * public record Point(double x, double y, double z) {}
 *
 * var typeSupport = Ros2TypeSupport.of(Point.class);
 * </pre>
 */
public final class Ros2TypeSupport {
    private Ros2TypeSupport() {}

    /**
     * Converts ROS2 type name to DDS type name.
     * <pre>
     * std_msgs/msg/String      → std_msgs::msg::dds_::String_
     * geometry_msgs/msg/Point  → geometry_msgs::msg::dds_::Point_
     * </pre>
     */
    public static String toDdsTypeName(String rosTypeName) {
        // std_msgs/msg/String → ["std_msgs", "msg", "String"]
        String[] parts = rosTypeName.split("/");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid ROS2 type name: " + rosTypeName);
        }
        // package::msg::dds_::TypeName_
        String pkg = parts[0];
        String msgOrSrv = parts[1];
        String typeName = parts[2];
        return pkg + "::" + msgOrSrv + "::dds_::" + typeName + "_";
    }

    /**
     * Creates a TypeSupport for a record, deriving the DDS type name from package structure.
     *
     * @param type the record class (must be in a package like xxx.msg)
     * @return TypeSupport with ROS2-compatible DDS type name
     */
    public static <T extends Record> TypeSupport<T> of(Class<T> type) {
        String ddsTypeName = toDdsTypeName(type);
        return TypeSupport.forCdrRecord(type, ddsTypeName);
    }

    /**
     * Creates a TypeSupport for a record with explicit ROS2 type name.
     * <pre>
     * Ros2TypeSupport.of(Point.class, "geometry_msgs/msg/Point")
     * </pre>
     *
     * @param type the record class
     * @param rosTypeName ROS2 type name (e.g., "geometry_msgs/msg/Point")
     * @return TypeSupport with ROS2-compatible DDS type name
     */
    public static <T extends Record> TypeSupport<T> of(Class<T> type, String rosTypeName) {
        String ddsTypeName = toDdsTypeName(rosTypeName);
        return TypeSupport.forCdrRecord(type, ddsTypeName);
    }

    /**
     * Creates a TypeSupport for service messages.
     * ROS2 services use standard CDR_LE (0x01) for request/response serialization.
     * The related_sample_identity is conveyed via RTPS Inline QoS, not in the payload.
     *
     * @param type the record class
     * @param rosTypeName ROS2 type name (e.g., "example_interfaces/srv/AddTwoInts_Request")
     * @return TypeSupport with standard CDR serialization
     */
    public static <T extends Record> TypeSupport<T> forService(Class<T> type, String rosTypeName) {
        String ddsTypeName = toDdsTypeName(rosTypeName);
        return TypeSupport.forCdrRecord(type, ddsTypeName);
    }

    /**
     * Converts Java package/class to DDS type name.
     * <pre>
     * std_msgs.msg.String_      → std_msgs::msg::dds_::String_
     * geometry_msgs.msg.Point   → geometry_msgs::msg::dds_::Point_
     * </pre>
     * If class name already ends with _, no extra _ is added.
     */
    static String toDdsTypeName(Class<?> type) {
        String packageName = type.getPackageName();
        String className = type.getSimpleName();

        // Convert package dots to ::
        String ddsPackage = packageName.replace('.', ':').replace(":", "::");

        // Add _ suffix only if not already present
        String ddsClassName = className.endsWith("_") ? className : className + "_";

        return ddsPackage + "::dds_::" + ddsClassName;
    }
}
