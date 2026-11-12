package ru.matsukov.raster;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Чтение по смещениям с кэшем 4 КиБ. Не загружает большой файл целиком. */
final class BinaryReader implements AutoCloseable {
    private final FileChannel channel;
    final long size;
    long bytesRead;
    private final byte[] cache = new byte[4096];
    private long cacheStart = -1;
    private int cacheLength;
    BinaryReader(Path path) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        size = channel.size();
    }
    void check(long offset, long length) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Остановлено");
        if (offset < 0 || length < 0 || offset > size || length > size - offset)
            throw new EOFException("Смещение или длина за границами файла: " + offset + " + " + length);
    }
    int u8(long p) throws IOException {
        check(p, 1);
        if (p < cacheStart || p >= cacheStart + cacheLength) {
            cacheStart = p;
            ByteBuffer buffer = ByteBuffer.wrap(cache, 0, (int)Math.min(cache.length, size - p));
            cacheLength = 0;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, p + cacheLength);
                if (n < 0) throw new EOFException("Файл изменён во время чтения");
                cacheLength += n;
            }
            bytesRead += cacheLength;
        }
        return cache[(int)(p - cacheStart)] & 255;
    }
    int u16(long p, boolean le) throws IOException {
        int a = u8(p), b = u8(p + 1);
        return le ? a | b << 8 : a << 8 | b;
    }
    long u32(long p, boolean le) throws IOException {
        long a = u16(p, le), b = u16(p + 2, le);
        return le ? a | b << 16 : a << 16 | b;
    }
    int i32(long p, boolean le) throws IOException { return (int)u32(p, le); }
    String ascii(long p, int n) throws IOException {
        check(p, n);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte)u8(p + i);
        return new String(b, StandardCharsets.ISO_8859_1);
    }
    @Override public void close() throws IOException { channel.close(); }
}
