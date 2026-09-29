package jrosx;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ROS2 Service request/response header for correlation.
 * Contains client GUID and sequence number to match requests with responses.
 */
public record ServiceHeader(byte[] clientGuid, long sequenceNumber) {

    private static final byte[] LOCAL_GUID = generateGuid();
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    /**
     * Create a new request header with auto-generated sequence number.
     */
    public static ServiceHeader newRequest() {
        return new ServiceHeader(LOCAL_GUID.clone(), SEQUENCE.incrementAndGet());
    }

    /**
     * Create a response header matching a request.
     */
    public static ServiceHeader forResponse(ServiceHeader request) {
        return new ServiceHeader(request.clientGuid().clone(), request.sequenceNumber());
    }

    /**
     * Check if this header matches another (same client and sequence).
     */
    public boolean matches(ServiceHeader other) {
        return Arrays.equals(this.clientGuid, other.clientGuid)
                && this.sequenceNumber == other.sequenceNumber;
    }

    private static byte[] generateGuid() {
        UUID uuid = UUID.randomUUID();
        byte[] guid = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            guid[i] = (byte) (msb >>> (56 - i * 8));
            guid[i + 8] = (byte) (lsb >>> (56 - i * 8));
        }
        return guid;
    }
}
