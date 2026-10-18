package org.tyler.service.agent.python;

import org.springframework.boot.SpringApplication;
import org.tyler.TylerAgentApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Full backend child for the unchanged desktop nonce/port handshake. */
public final class ReadinessOwner {
    private ReadinessOwner() { }
    public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(TylerAgentApplication.class, Arrays.copyOfRange(args, 1, args.length));
        Files.writeString(Path.of(args[0]), Long.toString(context.getBean(PythonWorker.class).pid()));
    }
}
