package jrosx;

import ddsj.dds.qos.DataReaderQos;
import ddsj.dds.qos.DataWriterQos;
import ddsj.dds.qos.policies.*;

/**
 * ROS 2 compatible QoS profiles.
 */
public final class Ros2QosProfiles {

    private Ros2QosProfiles() {}

    /** ROS 2 action status: reliable, transient local, keep last 1. */
    public static DataWriterQos actionStatusWriter() {
        return DataWriterQos.builder().reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.transientLocal()).history(HistoryQosPolicy.keepLast(1)).build();
    }

    public static DataReaderQos actionStatusReader() {
        return DataReaderQos.builder().reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.transientLocal()).history(HistoryQosPolicy.keepLast(1)).build();
    }


    /**
     * Default QoS profile matching ROS 2 defaults.
     * Reliability: RELIABLE, Durability: VOLATILE, History: KEEP_LAST(10)
     */
    public static DataWriterQos defaultWriter() {
        return DataWriterQos.builder()
                .reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(10))
                .deadline(DeadlineQosPolicy.infinite())
                .liveliness(LivelinessQosPolicy.automatic())
                .build();
    }

    public static DataReaderQos defaultReader() {
        return DataReaderQos.builder()
                .reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(10))
                .deadline(DeadlineQosPolicy.infinite())
                .liveliness(LivelinessQosPolicy.automatic())
                .build();
    }

    /**
     * Sensor data profile: best effort, volatile.
     */
    public static DataWriterQos sensorDataWriter() {
        return DataWriterQos.builder()
                .reliability(ReliabilityQosPolicy.bestEffort())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(5))
                .build();
    }

    public static DataReaderQos sensorDataReader() {
        return DataReaderQos.builder()
                .reliability(ReliabilityQosPolicy.bestEffort())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(5))
                .build();
    }

    /**
     * Service QoS profile: reliable, volatile.
     * Services require reliable communication.
     */
    public static DataWriterQos serviceWriter() {
        return DataWriterQos.builder()
                .reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(10))
                .deadline(DeadlineQosPolicy.infinite())
                .liveliness(LivelinessQosPolicy.automatic())
                .build();
    }

    public static DataReaderQos serviceReader() {
        return DataReaderQos.builder()
                .reliability(ReliabilityQosPolicy.reliable())
                .durability(DurabilityQosPolicy.volatile_())
                .history(HistoryQosPolicy.keepLast(10))
                .deadline(DeadlineQosPolicy.infinite())
                .liveliness(LivelinessQosPolicy.automatic())
                .build();
    }
}
