package ru.matsukov.raster;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Ограниченная очередь задач и результатов; никаких обращений к JavaFX. */
public final class ScanSession {
    public static final int LIMIT = 100_000;
    final BlockingQueue<ImageInfo> results = new ArrayBlockingQueue<>(512);
    final AtomicBoolean cancelled = new AtomicBoolean(), finished = new AtomicBoolean();
    final AtomicInteger discovered = new AtomicInteger(), completed = new AtomicInteger();
    volatile String phase = "Поиск файлов…", warning = "";
    final long started = System.nanoTime();
    volatile long ended;
    private final List<Path> roots;
    private final int threads;
    private Thread coordinator;
    public ScanSession(List<Path> roots, int threads) {
        this.roots = List.copyOf(roots); this.threads = Math.max(1, Math.min(threads, 16));
    }
    public void start() {
        coordinator = new Thread(this::run, "scan-coordinator"); coordinator.setDaemon(true); coordinator.start();
    }
    public void cancel() { cancelled.set(true); if (coordinator != null) coordinator.interrupt(); }
    double seconds() { return ((ended == 0 ? System.nanoTime() : ended) - started) / 1_000_000_000.0; }
    private void run() {
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "image-parser"); t.setDaemon(true); return t; });
        try {
            Set<Path> unique = new LinkedHashSet<>();
            for (Path root : roots) {
                if (cancelled.get() || unique.size() >= LIMIT) break;
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (cancelled.get()) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile()) { unique.add(file.toAbsolutePath().normalize()); discovered.set(unique.size()); }
                        if (unique.size() >= LIMIT) { warning = "Достигнут лимит 100 000 файлов. Остальные не включены."; return FileVisitResult.TERMINATE; }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                        warning = "Не удалось перечислить часть файлов: " + file + " — " + e.getMessage();
                        return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                        if (e != null) warning = "Ошибка обхода: " + dir + " — " + e.getMessage();
                        return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            }
            phase = "Чтение заголовков…";
            CompletionService<ImageInfo> service = new ExecutorCompletionService<>(pool);
            Iterator<Path> it = unique.iterator(); int pending = 0;
            while (!cancelled.get() && (it.hasNext() || pending > 0)) {
                while (!cancelled.get() && it.hasNext() && pending < threads * 2) {
                    Path p = it.next(); service.submit(() -> ImageParser.inspect(p)); pending++;
                }
                if (pending == 0) break;
                ImageInfo result = service.take().get(); pending--;
                results.put(result); completed.incrementAndGet();
            }
        } catch (InterruptedException e) { cancelled.set(true); Thread.currentThread().interrupt(); }
        catch (IOException | ExecutionException | RuntimeException e) { warning = "Ошибка обработки: " + e.getMessage(); }
        finally { pool.shutdownNow(); ended = System.nanoTime(); phase = cancelled.get() ? "Остановлено" : "Готово"; finished.set(true); }
    }
}
