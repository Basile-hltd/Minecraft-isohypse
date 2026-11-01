package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A big array kept in a memory-mapped file instead of the Java heap, so that the maps of the build ({@link TempLayout}) are
 * limited by the disk and not by the heap. Either a scratch file, created in a folder and deleted when closed, or a
 * read-only window on a part of an existing file (which is left alone).
 */
final class MappedFile implements AutoCloseable {

    static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    /** Scratch files are in the byte order of the machine, they never leave the build. */
    static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private static final String PREFIX = "isohypse-";
    private static final String SUFFIX = ".tmp";

    private final Arena arena = Arena.ofShared();
    private final MemorySegment segment;
    private final Path scratch; // deleted on close, null for a window on an existing file

    private MappedFile(Path path, FileChannel.MapMode mode, long offset, long bytes, boolean scratch) throws IOException {
        StandardOpenOption[] options = scratch
                ? new StandardOpenOption[]{StandardOpenOption.READ, StandardOpenOption.WRITE}
                : new StandardOpenOption[]{StandardOpenOption.READ};
        try (FileChannel channel = FileChannel.open(path, options)) {
            this.segment = channel.map(mode, offset, bytes, arena);
        }
        this.scratch = scratch ? path : null;
    }

    /** A new zero-filled scratch file of this size in {@code dir}. */
    static MappedFile scratch(File dir, long bytes) throws IOException {
        Path path = Files.createTempFile(dir.toPath(), PREFIX, SUFFIX);
        try {
            return new MappedFile(path, FileChannel.MapMode.READ_WRITE, 0, bytes, true);
        } catch (IOException | RuntimeException | Error e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    /** A read-only window on {@code bytes} bytes of {@code file} from {@code offset}. */
    static MappedFile readOnly(File file, long offset, long bytes) throws IOException {
        return new MappedFile(file.toPath(), FileChannel.MapMode.READ_ONLY, offset, bytes, false);
    }

    /** Deletes the scratch files an interrupted build left in {@code dir}. */
    static void deleteStale(File dir) throws IOException {
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir.toPath(), PREFIX + "*" + SUFFIX)) {
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }
        }
    }

    MemorySegment segment() {
        return segment;
    }

    /** Unmaps the file and, for a scratch file, deletes it (a mapped file cannot be deleted on Windows). */
    @Override
    public void close() {
        arena.close();
        if (scratch != null) {
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
