package jrosx;

import example_interfaces.action.Fibonacci;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static jrosx.ActionMessages.*;

class ActionCodecTest {
    private static final UUID ID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f");
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ", "")); }

    @Test void goalUuidIsFixedOctetsWithoutSequencePrefix() {
        var value = new SendGoalRequest<>(ID, new Fibonacci.Goal(6));
        byte[] expected = hex("00010000 000102030405060708090a0b0c0d0e0f 06000000");
        assertArrayEquals(expected, Fibonacci.TYPE.sendRequest.serialize(value));
        assertEquals(value, Fibonacci.TYPE.sendRequest.deserialize(expected));
        assertEquals("example_interfaces::action::dds_::Fibonacci_SendGoal_Request_", Fibonacci.TYPE.sendRequest.getTypeName());
    }

    @Test void acceptanceAndResultHaveCorrectPadding() {
        var accepted = new SendGoalResponse(true, new Time(42, 123));
        assertArrayEquals(hex("00010000 01000000 2a000000 7b000000"), Fibonacci.TYPE.sendResponse.serialize(accepted));
        var result = new GetResultResponse<>(ActionStatus.SUCCEEDED, new Fibonacci.Result(new int[]{0, 1, 1, 2}));
        byte[] expected = hex("00010000 04000000 04000000 00000000 01000000 01000000 02000000");
        assertArrayEquals(expected, Fibonacci.TYPE.resultResponse.serialize(result));
        assertArrayEquals(result.result().sequence(), Fibonacci.TYPE.resultResponse.deserialize(expected).result().sequence());
    }

    @Test void statusSequenceAlignmentAndCancelRoundTrip() {
        var info = new GoalInfo(ID, new Time(42, 123));
        var statuses = new StatusArray(List.of(new GoalStatus(info, ActionStatus.EXECUTING),
                new GoalStatus(info, ActionStatus.CANCELED)));
        byte[] expected = hex("00010003 02000000 000102030405060708090a0b0c0d0e0f 2a000000 7b000000 02 "
                + "000102030405060708090a0b0c0d0e0f 000000 2a000000 7b000000 05 000000");
        assertArrayEquals(expected, ActionType.STATUS.serialize(statuses));
        assertEquals(statuses, ActionType.STATUS.deserialize(expected));
        var cancel = new CancelResponse(CancelResponse.NONE, List.of(info, info));
        assertEquals(cancel, ActionType.CANCEL_RESPONSE.deserialize(ActionType.CANCEL_RESPONSE.serialize(cancel)));
    }

    record Wide(long value) {}
    record Empty() {}
    @Test void nestedResultUsesOuterAlignmentAndEmptyMessageHasPlaceholder() {
        var type = new ActionType<>("test/action/Wide", Wide.class, Wide.class, Empty.class, () -> new Wide(0));
        assertArrayEquals(hex("00010000 0400000000000000 0807060504030201"),
                type.resultResponse.serialize(new GetResultResponse<>(ActionStatus.SUCCEEDED, new Wide(0x0102030405060708L))));
        assertEquals(24, type.feedback.serialize(new FeedbackMessage<>(ID, new Empty())).length);
        assertThrows(IllegalArgumentException.class, () -> ActionType.STATUS.deserialize(hex("00010000 ffffffff")));
        assertThrows(IllegalArgumentException.class, () -> Fibonacci.TYPE.sendResponse.deserialize(hex("00010000 02000000 00000000 00000000")));
    }
}
