package jrosx;

import ddsj.dds.sample.Sample;
import ddsj.rtps.message.SampleIdentity;

/** Request identity rules shared by ROS services and actions. */
final class RpcIdentity {
    private RpcIdentity() {}

    static SampleIdentity request(Sample<?> sample) {
        // Fast DDS advertises its response reader GUID in related_sample_identity.
        // Otherwise reply using the actual request writer GUID.
        var guid = sample.relatedSampleIdentity().map(SampleIdentity::writerGuid)
                .or(sample::writerGuid)
                .orElseThrow(() -> new IllegalArgumentException("Missing RPC request identity"));
        // The request DATA sequence is authoritative, not the related identity's sequence.
        return new SampleIdentity(guid, sample.writerSequenceNumber());
    }
}
