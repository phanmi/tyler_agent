package org.tyler.architecture;

import org.junit.jupiter.api.Test;
import org.tyler.TylerAgentApplication;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks compiled production dependencies, including method bodies and generic signatures. */
class ServiceBoundaryTest {

    @Test
    void javaProductionHasNoProviderDependencies() throws Exception {
        Path classes = Path.of(TylerAgentApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(classes.resolve("org/tyler"))) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/').replaceFirst("\\.class$", "");
                for (var entry : ClassFile.of().parse(file).constantPool()) {
                    if (entry instanceof Utf8Entry text && text.stringValue().replace('.', '/').contains("com/openai/")) {
                        violations.add(name + ": provider dependency belongs in Python");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "New Java provider dependencies: " + violations);
    }

    @Test
    void controllersAndBridgeCannotReferenceStorageTypes() throws Exception {
        Path classes = Path.of(TylerAgentApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        for (String layer : List.of("controller", "service/agent/bridge")) {
            Path directory = classes.resolve("org/tyler/" + layer);
            try (var paths = Files.walk(directory)) {
                List<Path> classFiles = paths.filter(path -> path.toString().endsWith(".class")).toList();
                assertFalse(classFiles.isEmpty(), "No production classes found for " + layer);
                for (Path classFile : classFiles) {
                    for (var entry : ClassFile.of().parse(classFile).constantPool()) {
                        if (entry instanceof Utf8Entry text) {
                            String value = text.stringValue().replace('.', '/');
                            if (value.contains("org/tyler/dao/") || value.contains("org/tyler/dal/")) {
                                violations.add(classes.relativize(classFile) + ": " + text.stringValue());
                            }
                            if (layer.equals("service/agent/bridge") && (value.contains("com/openai/") || value.contains("java/sql/")
                                    || value.contains("org/springframework/jdbc/"))) {
                                violations.add(classes.relativize(classFile) + ": " + text.stringValue());
                            }
                            if (classFile.getFileName().toString().equals("ServiceOperationDispatcher.class")
                                    && ((value.contains("org/tyler/service/") && !value.contains("org/tyler/service/agent/"))
                                    || value.contains("org/tyler/filesandbox/") || value.contains("org/tyler/model/"))) {
                                violations.add("Dispatcher must route through handlers: " + text.stringValue());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Call a feature service instead of storage: " + violations);
    }
}
