package org.tyler.service.agent.bridge;

import org.tyler.service.agent.bridge.handler.CurrentTimeHandler;
import org.tyler.service.agent.bridge.handler.FileReadHandler;
import org.tyler.service.agent.bridge.handler.FileWriteHandler;
import org.tyler.service.agent.bridge.handler.FoodInsertHandler;
import org.tyler.service.agent.bridge.handler.UserInfoReadHandler;
import org.tyler.service.agent.bridge.handler.WorkoutPlanSaveHandler;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.food.IFoodService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

import java.time.Clock;
import java.util.List;

/** Explicit isolated test assembly; production discovers handlers through Spring. */
final class BridgeTestSupport {
    private BridgeTestSupport() { }

    static ServiceOperationDispatcher dispatcher(IFoodService food, IWorkoutPlanService plans,
            IUserInfoService profile, IFileSandboxRead reader, IFileSandboxWrite writer, Clock clock) {
        var mapper = new OperationPayloadMapper();
        return new ServiceOperationDispatcher(List.of(new FoodInsertHandler(food, mapper),
                new WorkoutPlanSaveHandler(plans, mapper), new UserInfoReadHandler(profile, mapper),
                new FileReadHandler(reader), new FileWriteHandler(writer), new CurrentTimeHandler(clock)),
                new MessageValidator());
    }
}
