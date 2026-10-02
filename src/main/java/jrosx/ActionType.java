package jrosx;

import ddsj.cdr.*;
import ddsj.dds.core.TypeSupport;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import static jrosx.ActionMessages.*;
import static jrosx.ActionCdr.*;

/** The ROS package/action/Type name and the three application record types. */
public final class ActionType<G extends Record, R extends Record, F extends Record> {
    private final String name;
    private final Supplier<R> emptyResult;
    final TypeSupport<SendGoalRequest<G>> sendRequest;
    final TypeSupport<SendGoalResponse> sendResponse;
    final TypeSupport<GetResultRequest> resultRequest;
    final TypeSupport<GetResultResponse<R>> resultResponse;
    final TypeSupport<FeedbackMessage<F>> feedback;

    public ActionType(String name, Class<G> goalType, Class<R> resultType, Class<F> feedbackType,
                      Supplier<R> emptyResult) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*/action/[A-Za-z][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("Expected package/action/Type");
        var goal = record(goalType);
        var result = record(resultType);
        var feedbackBody = record(feedbackType);
        this.name = name;
        this.emptyResult = Objects.requireNonNull(emptyResult);
        sendRequest = support(SendGoalRequest.class, name + "_SendGoal_Request", codec(16,
                (out, value) -> { UUID_CODEC.write(out, value.goalId()); goal.write(out, value.goal()); },
                in -> new SendGoalRequest<>(UUID_CODEC.read(in), goal.read(in))));
        sendResponse = support(SendGoalResponse.class, name + "_SendGoal_Response", codec(9,
                (out, value) -> { out.writeBoolean(value.accepted()); TIME.write(out, value.stamp()); },
                in -> new SendGoalResponse(in.readBoolean(), TIME.read(in))));
        resultRequest = support(GetResultRequest.class, name + "_GetResult_Request", codec(16,
                (out, value) -> UUID_CODEC.write(out, value.goalId()),
                in -> new GetResultRequest(UUID_CODEC.read(in))));
        resultResponse = support(GetResultResponse.class, name + "_GetResult_Response", codec(1,
                (out, value) -> { out.writeByte(value.status().code()); result.write(out, value.result()); },
                in -> new GetResultResponse<>(ActionStatus.fromCode(in.readByte()), result.read(in))));
        feedback = support(FeedbackMessage.class, name + "_FeedbackMessage", codec(16,
                (out, value) -> { UUID_CODEC.write(out, value.goalId()); feedbackBody.write(out, value.feedback()); },
                in -> new FeedbackMessage<>(UUID_CODEC.read(in), feedbackBody.read(in))));
        resultResponse.serialize(new GetResultResponse<>(ActionStatus.UNKNOWN, emptyResult()));
    }

    public String getName() { return name; }
    R emptyResult() { return Objects.requireNonNull(emptyResult.get(), "emptyResult returned null"); }

    private static final CdrCodec<Time> TIME = record(Time.class);
    private static final CdrCodec<GoalInfo> INFO = codec(24,
            (out, value) -> { UUID_CODEC.write(out, value.goalId()); TIME.write(out, value.stamp()); },
            in -> new GoalInfo(UUID_CODEC.read(in), TIME.read(in)));
    private static final CdrCodec<GoalInfo[]> INFOS = CdrCodecs.sequence(GoalInfo[].class, INFO);
    private static final CdrCodec<GoalStatus> GOAL_STATUS = codec(25,
            (out, value) -> { INFO.write(out, value.goalInfo()); out.writeByte(value.status().code()); },
            in -> new GoalStatus(INFO.read(in), ActionStatus.fromCode(in.readByte())));
    private static final CdrCodec<GoalStatus[]> STATUSES = CdrCodecs.sequence(GoalStatus[].class, GOAL_STATUS);

    static final TypeSupport<CancelRequest> CANCEL_REQUEST = support(CancelRequest.class,
            "action_msgs/srv/CancelGoal_Request", codec(24,
            (out, value) -> INFO.write(out, value.goalInfo()), in -> new CancelRequest(INFO.read(in))));
    static final TypeSupport<CancelResponse> CANCEL_RESPONSE = support(CancelResponse.class,
            "action_msgs/srv/CancelGoal_Response", codec(5, (out, value) -> {
                out.writeByte(value.returnCode());
                INFOS.write(out, value.goalsCanceling().toArray(GoalInfo[]::new));
            }, in -> new CancelResponse(in.readByte(), List.of(INFOS.read(in)))));
    static final TypeSupport<StatusArray> STATUS = support(StatusArray.class,
            "action_msgs/msg/GoalStatusArray", codec(4,
            (out, value) -> STATUSES.write(out, value.statusList().toArray(GoalStatus[]::new)),
            in -> new StatusArray(List.of(STATUSES.read(in)))));
}
