package org.tyler.service.agent.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Child JVM used to force-kill the actual Java owner while Python is blocked in simulated provider work. */
public final class JavaWorkerOwner {
    private JavaWorkerOwner() { }

    public static void main(String[] args) throws Exception {
        var settings = PythonAgentSettings.defaults(List.of(args[0], args[1], args[2]));
        try (var worker = new PythonWorker(settings, (name, input) -> null)) {
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
            worker.start();
            Files.writeString(Path.of(args[3]), Long.toString(worker.pid()));
            worker.execute(WorkerTestSupport.payload());
        }
    }
}
