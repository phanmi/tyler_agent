package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.UserInfoReadHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class UserInfoReadHandlerTest {
    @Test
    void preservesNullComponentsAndFailsOnNullProfileOrStorageError() {
        var service = mock(org.tyler.service.userInfo.IUserInfoService.class);
        var handler = new UserInfoReadHandler(service, new OperationPayloadMapper());
        when(service.get()).thenReturn(new org.tyler.model.userInfo.UserInfo(null, null));
        var result = handler.execute(input("profile")).get("profile");
        assertTrue(result.get("User").isNull());
        assertTrue(result.get("Other").isNull());
        when(service.get()).thenReturn(null);
        assertEquals("Stored agent profile is null", assertThrowsExactly(IllegalStateException.class,
                () -> handler.execute(input("profile"))).getMessage());
        var original = new org.tyler.exceptionHandler.exception.FileReadException("read failed");
        when(service.get()).thenThrow(original);
        assertSame(original, assertThrowsExactly(org.tyler.exceptionHandler.exception.FileReadException.class,
                () -> handler.execute(input("profile"))));
    }
}
