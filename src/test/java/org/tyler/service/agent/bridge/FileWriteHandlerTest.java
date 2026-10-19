package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.FileWriteHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class FileWriteHandlerTest {
    @Test
    void acknowledgesEmptyWriteOnlyAfterSuccessAndRetainsFailure() {
        var writer = mock(org.tyler.filesandbox.IFileSandboxWrite.class);
        var handler = new FileWriteHandler(writer);
        var request = input("file_write").put("content", "");
        assertEquals("notes/session.txt", handler.execute(request).get("path").textValue());
        verify(writer).write("notes/session.txt", "");
        var original = new org.tyler.exceptionHandler.exception.FileWriteException("write failed");
        doThrow(original).when(writer).write("notes/session.txt", "");
        assertSame(original, assertThrowsExactly(org.tyler.exceptionHandler.exception.FileWriteException.class,
                () -> handler.execute(request)));
    }
}
