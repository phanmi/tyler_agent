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
    void controllersAndToolsCannotReferenceStorageTypes() throws Exception {
        Path classes = Path.of(TylerAgentApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        for (String layer : List.of("controller", "tool")) {
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
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Call a feature service instead of storage: " + violations);
    }
}
