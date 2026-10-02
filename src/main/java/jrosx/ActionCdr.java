package jrosx;

import ddsj.cdr.*;
import ddsj.dds.core.CdrRecordTypeSupport;
import ddsj.dds.core.TypeSupport;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** ROS action envelopes composed from DDSJ's CDR body codecs. */
final class ActionCdr {
    private ActionCdr() {}

    static <T> CdrCodec<T> codec(int minimum, BiConsumer<CdrWriter, T> write, Function<CdrReader, T> read) {
        return new CdrCodec<>() {
            public void write(CdrWriter out, T value) { write.accept(out, value); }
            public T read(CdrReader in) { return read.apply(in); }
            public int minimumSize() { return minimum; }
        };
    }
    @SuppressWarnings("unchecked")
    static <T> TypeSupport<T> support(Class<?> type, String name, CdrCodec<T> codec) {
        return TypeSupport.fromSerializer((Class<T>) type, Ros2TypeSupport.toDdsTypeName(name),
                new CdrPayloadSerializer<>(codec));
    }
    static <T extends Record> CdrCodec<T> record(Class<T> type) {
        var body = CdrRecordTypeSupport.of(type).codec();
        if (type.getRecordComponents().length != 0) return body;
        // rosidl represents an empty message with one placeholder octet.
        return codec(1, (out, value) -> { body.write(out, value); out.writeByte((byte) 0); },
                in -> { in.readByte(); return body.read(in); });
    }
    static final CdrCodec<UUID> UUID_CODEC = codec(16, (out, id) -> {
        for (int i = 7; i >= 0; i--) out.writeByte((byte) (id.getMostSignificantBits() >>> (8 * i)));
        for (int i = 7; i >= 0; i--) out.writeByte((byte) (id.getLeastSignificantBits() >>> (8 * i)));
    }, in -> {
        long high = 0, low = 0;
        for (int i = 0; i < 8; i++) high = (high << 8) | (in.readByte() & 255L);
        for (int i = 0; i < 8; i++) low = (low << 8) | (in.readByte() & 255L);
        return new UUID(high, low);
    });
}
