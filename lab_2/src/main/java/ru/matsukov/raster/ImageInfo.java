package ru.matsukov.raster;

import java.nio.file.Path;
import java.util.Locale;

/** Один результат. После публикации в очередь больше не изменяется. */
public final class ImageInfo {
    public final Path path;
    public long size, bytesRead, width, height;
    public int bits;
    public double dpiX, dpiY;
    public String format = "—", compression = "—", details = "", error = "";
    public String status = "OK";
    public ImageInfo(Path path) { this.path = path; }
    public String name() { return path.getFileName().toString(); }
    public String dimensions() { return width > 0 && height > 0 ? width + " × " + height : "—"; }
    public String dpi() { return dpiX > 0 && dpiY > 0 ? String.format(Locale.ROOT, "%.2f × %.2f", dpiX, dpiY) : "Не задано"; }
    public String depth() { return bits > 0 ? Integer.toString(bits) : "—"; }
    public String description() {
        return "Файл: " + path + "\nФормат: " + format + "\nРазмер: " + dimensions() + " px\nDPI: " + dpi()
            + "\nГлубина: " + depth() + " бит/пиксель\nСжатие: " + compression + "\nРазмер файла: " + size
            + " байт\nПрочитано: " + bytesRead + " байт\nСтатус: " + status + "\n" + error + "\n" + details;
    }
}
