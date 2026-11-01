package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.FileReadHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class FileReadHandlerTest {
    @Test
    void preservesTextAndSandboxFailure() {
        var reader = mock(org.tyler.filesandbox.IFileSandboxRead.class);
        var handler = new FileReadHandler(reader, mock(ProtectedWorkspaceFiles.class));
        String content = "Unicode: \u00e9\nline\r\n";
        when(reader.read("notes/session.txt")).thenReturn(content);
        assertEquals(content, handler.execute(input("file_read")).get("content").textValue());
        var original = new org.tyler.exceptionHandler.exception.FileReadException("read failed");
        when(reader.read("notes/session.txt")).thenThrow(original);
        assertSame(original, assertThrowsExactly(org.tyler.exceptionHandler.exception.FileReadException.class,
                () -> handler.execute(input("file_read"))));
    }
}
