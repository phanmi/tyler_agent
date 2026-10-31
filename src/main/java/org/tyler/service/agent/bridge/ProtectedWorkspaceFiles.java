package org.tyler.service.agent.bridge;

import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.IApplicationOwnedFiles;
import org.tyler.filesandbox.IFileSandboxPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Application files the model's file tools must never read or write: every path declared by an
 * {@link IApplicationOwnedFiles} service (provider keys, provider settings, chat history). The
 * owning services use the sandbox directly.
 *
 * <p>Paths are compared after sandbox resolution in Windows' most permissive form (case and
 * trailing dots or spaces ignored) on every OS, and by file identity when both files exist, so
 * {@code ./APIKEY.txt}, {@code notes/../apikey.txt} and 8.3 short names are refused as well.
 * The sandbox root is fixed, so the owned paths are resolved once.
 */
@Component
public class ProtectedWorkspaceFiles {

    public static final String RESERVED_MESSAGE = "This workspace file is reserved for the application";

    private static final Pattern TRAILING_DOTS_AND_SPACES = Pattern.compile("[. ]+$");

    private final IFileSandboxPath paths;
    private final List<Path> reserved;
    private final Set<String> reservedKeys;

    public ProtectedWorkspaceFiles(IFileSandboxPath paths, List<IApplicationOwnedFiles> owners) {
        this.paths = paths;
        this.reserved = owners.stream().flatMap(owner -> owner.ownedPaths().stream()).map(paths::resolve).toList();
        this.reservedKeys = reserved.stream().map(ProtectedWorkspaceFiles::comparable).collect(Collectors.toSet());
    }

    /** Throws {@link WorkspacePathException} when the model-supplied path names a reserved file. */
    public void requireUnprotected(String relativePath) {
        Path target = paths.resolve(relativePath);
        if (reservedKeys.contains(comparable(target)) || aliasOfExistingReservedFile(target)) {
            throw new WorkspacePathException(RESERVED_MESSAGE);
        }
    }

    private static String comparable(Path path) {
        StringBuilder text = new StringBuilder(String.valueOf(path.getRoot()).toLowerCase(Locale.ROOT));
        for (Path element : path) {
            text.append('/').append(
                    TRAILING_DOTS_AND_SPACES.matcher(element.toString()).replaceAll("").toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }

    /** Short names and other aliases exist only for existing files; reserved files may appear later. */
    private boolean aliasOfExistingReservedFile(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            for (Path file : reserved) {
                try {
                    if (Files.isSameFile(target, file)) {
                        return true;
                    }
                } catch (NoSuchFileException e) {
                    // This reserved file does not exist yet.
                }
            }
            return false;
        } catch (IOException e) {
            throw new FileReadException("Unable to inspect workspace path", e);
        }
    }
}
