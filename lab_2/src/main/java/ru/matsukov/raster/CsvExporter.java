package ru.matsukov.raster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class CsvExporter {
    private CsvExporter() {}
    public static void write(Path target, List<ImageInfo> rows) throws IOException {
        // Сначала временный файл: ошибка записи не уничтожит предыдущий экспорт.
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".raster-", ".csv");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                out.write('\ufeff');
                line(out, "Путь", "Формат", "Ширина", "Высота", "DPI X", "DPI Y", "Бит/пиксель", "Сжатие", "Размер, байт", "Статус", "Ошибка", "Дополнительно");
                for (ImageInfo r : rows) line(out, r.path.toString(), r.format, Long.toString(r.width), Long.toString(r.height),
                    r.dpiX > 0 ? Double.toString(r.dpiX) : "", r.dpiY > 0 ? Double.toString(r.dpiY) : "", r.depth(), r.compression,
                    Long.toString(r.size), r.status, r.error, r.details);
            }
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void line(Writer out, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.write(';');
            String s = cells[i] == null ? "" : cells[i];
            if (!s.isEmpty() && "=+-@\t\r\n".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            out.write('"'); out.write(s.replace("\"", "\"\"")); out.write('"');
        }
        out.write("\r\n");
    }
}
