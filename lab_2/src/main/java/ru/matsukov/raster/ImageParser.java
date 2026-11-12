package ru.matsukov.raster;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Собственные парсеры: ImageIO, AWT и библиотеки метаданных не используются. */
public final class ImageParser {
    private ImageParser() {}
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    public static ImageInfo inspect(Path path) {
        ImageInfo info = new ImageInfo(path);
        try (BinaryReader r = new BinaryReader(path)) {
            info.size = r.size;
            try {
                String s = r.ascii(0, (int)Math.min(8, r.size));
                if (s.equals("\u0089PNG\r\n\u001a\n")) { info.format = "PNG"; png(r, info); }
                else if (s.startsWith("\u00ff\u00d8")) { info.format = "JPEG"; jpeg(r, info); }
                else if (s.startsWith("GIF87a") || s.startsWith("GIF89a")) { info.format = "GIF"; gif(r, info); }
                else if (s.startsWith("BM")) { info.format = "BMP"; bmp(r, info); }
                else if (s.startsWith("II") || s.startsWith("MM")) { info.format = "TIFF"; tiff(r, info, 0, r.size, false); }
                else if (!s.isEmpty() && s.charAt(0) == 10) { info.format = "PCX"; pcx(r, info); }
                else throw new IOException("Неизвестная сигнатура: возможно, подменено расширение");
                require(info.width > 0 && info.height > 0, "Неверные размеры изображения");
                String name = info.name().toLowerCase(Locale.ROOT);
                String ext = name.substring(name.lastIndexOf('.') + 1);
                boolean matches = switch (info.format) {
                    case "JPEG" -> Set.of("jpg", "jpeg", "jpe").contains(ext);
                    case "TIFF" -> Set.of("tif", "tiff").contains(ext);
                    case "BMP" -> Set.of("bmp", "dib").contains(ext);
                    default -> ext.equals(info.format.toLowerCase(Locale.ROOT));
                };
                if (!matches) info.details += "; расширение не соответствует сигнатуре";
            } catch (UnsupportedOperationException e) {
                info.status = "Не поддерживается"; info.error = e.getMessage();
            } catch (IOException | IllegalArgumentException e) {
                info.status = Thread.currentThread().isInterrupted() ? "Остановлено" : "Файл повреждён";
                info.error = e.getMessage();
            } finally { info.bytesRead = r.bytesRead; }
        } catch (IOException | SecurityException e) {
            info.status = "Ошибка доступа"; info.error = e.getMessage();
        }
        return info;
    }

    private static void png(BinaryReader r, ImageInfo o) throws IOException {
        long p = 8; boolean header = false, data = false, palette = false; int colorType = -1;
        while (p < r.size) {
            r.check(p, 12);
            long n = r.u32(p, false); String type = r.ascii(p + 4, 4);
            r.check(p, n + 12);
            require(header || type.equals("IHDR"), "PNG: IHDR должен быть первым");
            switch (type) {
                case "IHDR" -> {
                    require(!header && n == 13, "PNG: неверный IHDR"); header = true;
                    o.width = r.u32(p + 8, false); o.height = r.u32(p + 12, false);
                    require(o.width <= Integer.MAX_VALUE && o.height <= Integer.MAX_VALUE, "PNG: слишком большие размеры");
                    int depth = r.u8(p + 16); colorType = r.u8(p + 17);
                    boolean valid = switch (colorType) {
                        case 0 -> Set.of(1, 2, 4, 8, 16).contains(depth);
                        case 3 -> Set.of(1, 2, 4, 8).contains(depth);
                        case 2, 4, 6 -> depth == 8 || depth == 16;
                        default -> false;
                    };
                    require(valid, "PNG: недопустимые глубина/тип цвета");
                    int channels = switch (colorType) { case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 1; };
                    require(r.u8(p + 18) == 0 && r.u8(p + 19) == 0 && r.u8(p + 20) <= 1, "PNG: неверные методы");
                    o.bits = depth * channels; o.compression = "Deflate";
                    o.details = "Тип цвета: " + colorType + "; бит/компонент: " + depth
                        + "; метод фильтрации: 0 (адаптивный); interlace: " + r.u8(p + 20);
                }
                case "pHYs" -> {
                    require(n == 9, "PNG: неверный pHYs");
                    if (r.u8(p + 16) == 1) { o.dpiX = r.u32(p + 8, false) * .0254; o.dpiY = r.u32(p + 12, false) * .0254; }
                }
                case "PLTE" -> {
                    require(n > 0 && n <= 768 && n % 3 == 0, "PNG: неверная палитра"); palette = true;
                    o.details += "; палитра: " + n / 3 + " цветов";
                }
                case "IDAT" -> { require(colorType != 3 || palette, "PNG: отсутствует PLTE"); data = true; }
                case "IEND" -> { require(n == 0 && data, "PNG: нет IDAT или неверный IEND"); return; }
            }
            p += n + 12; // Перескок через сжатые пиксели без чтения IDAT.
        }
        throw new IOException("PNG: отсутствует IEND");
    }

    private static void jpeg(BinaryReader r, ImageInfo o) throws IOException {
        long p = 2; boolean sof = false, sos = false; double exifX = 0, exifY = 0;
        Set<Integer> frames = Set.of(0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf);
        while (p < r.size) {
            require(r.u8(p) == 255, "JPEG: ожидался маркер");
            while (r.u8(p) == 255) p++;
            int marker = r.u8(p++);
            if (marker == 0xd9) break;
            if (marker == 1 || marker >= 0xd0 && marker <= 0xd7) continue;
            int n = r.u16(p, false); require(n >= 2, "JPEG: неверная длина сегмента"); r.check(p, n);
            if (marker == 0xda) { require(n >= 6, "JPEG: короткий SOS"); sos = true; break; }
            if (frames.contains(marker)) {
                require(n >= 8, "JPEG: короткий SOF");
                int depth = r.u8(p + 2), components = r.u8(p + 7);
                require(components > 0 && n >= 8 + 3 * components, "JPEG: неверное число компонентов");
                o.height = r.u16(p + 3, false); o.width = r.u16(p + 5, false); o.bits = depth * components;
                o.compression = Set.of(0xc2,0xc6,0xca,0xce).contains(marker) ? "JPEG прогрессивный"
                    : Set.of(0xc3,0xc7,0xcb,0xcf).contains(marker) ? "JPEG lossless" : "JPEG DCT";
                o.details = "SOF: 0x" + Integer.toHexString(marker) + "; " + components + " компонентов × " + depth + " бит";
                sof = true;
            }
            if (marker == 0xe0 && n >= 16 && r.ascii(p + 2, 5).equals("JFIF\0")) {
                int unit = r.u8(p + 9); double factor = unit == 1 ? 1 : unit == 2 ? 2.54 : 0;
                o.dpiX = r.u16(p + 10, false) * factor; o.dpiY = r.u16(p + 12, false) * factor;
            }
            if (marker == 0xe1 && n >= 16 && r.ascii(p + 2, 6).equals("Exif\0\0")) {
                ImageInfo exif = new ImageInfo(o.path); tiff(r, exif, p + 8, p + n, true);
                if (exif.dpiX > 0 && exif.dpiY > 0) { exifX = exif.dpiX; exifY = exif.dpiY; }
            }
            p += n;
        }
        if (exifX > 0 && exifY > 0) { o.dpiX = exifX; o.dpiY = exifY; }
        require(sof && sos, "JPEG: отсутствует SOF или SOS");
        // Последовательный поиск в хвосте сохраняет эффективность кэша Reader.
        boolean end = false;
        for (long i = Math.max(p, r.size - 65536); i + 1 < r.size; i++)
            if (r.u8(i) == 255 && r.u8(i + 1) == 0xd9) { end = true; break; }
        require(end, "JPEG: EOI не найден в последних 64 КиБ");
    }

    private static void gif(BinaryReader r, ImageInfo o) throws IOException {
        r.check(0, 13); o.width = r.u16(6, true); o.height = r.u16(8, true);
        int packed = r.u8(10), colors = (packed & 128) != 0 ? 1 << ((packed & 7) + 1) : 0;
        o.bits = colors == 0 ? 0 : (packed & 7) + 1; o.compression = "LZW";
        long p = 13 + colors * 3L; r.check(13, colors * 3L); int frames = 0;
        while (p < r.size) {
            int block = r.u8(p++);
            if (block == 0x3b) {
                require(frames > 0, "GIF: нет кадров");
                o.details = "Кадров: " + frames + "; глобальная палитра: " + colors + " цветов; глубина — максимум бит индекса палитр";
                return;
            }
            if (block == 0x21) { r.check(p++, 1); p = gifBlocks(r, p); }
            else if (block == 0x2c) {
                r.check(p, 9);
                require(r.u16(p + 4, true) > 0 && r.u16(p + 6, true) > 0, "GIF: пустой кадр");
                int local = r.u8(p + 8); p += 9;
                if ((local & 128) != 0) {
                    int bits = (local & 7) + 1; o.bits = Math.max(o.bits, bits);
                    long length = 3L * (1 << bits); r.check(p, length); p += length;
                } else require(colors > 0, "GIF: нет палитры");
                int code = r.u8(p++); require(code >= 2 && code <= 8, "GIF: неверная длина кода LZW");
                p = gifBlocks(r, p); frames++;
            } else throw new IOException("GIF: неизвестный блок");
        }
        throw new IOException("GIF: отсутствует завершающий блок");
    }
    private static long gifBlocks(BinaryReader r, long p) throws IOException {
        for (;;) { int n = r.u8(p++); if (n == 0) return p; r.check(p, n); p += n; }
    }

    private static void bmp(BinaryReader r, ImageInfo o) throws IOException {
        r.check(0, 26);
        long fileSize = r.u32(2, true), pixels = r.u32(10, true), dib = r.u32(14, true);
        require(fileSize <= r.size && fileSize >= pixels, "BMP: неверный размер файла"); r.check(14, dib);
        int planes; long c = 0, palette = 0; int entrySize;
        if (dib == 12) {
            o.width = r.u16(18, true); o.height = r.u16(20, true); planes = r.u16(22, true); o.bits = r.u16(24, true); entrySize = 3;
        } else {
            if (dib < 40) throw new UnsupportedOperationException("DIB " + dib + " не поддерживается");
            o.width = r.i32(18, true); o.height = Math.abs((long)r.i32(22, true));
            planes = r.u16(26, true); o.bits = r.u16(28, true); c = r.u32(30, true); entrySize = 4;
            int x = r.i32(38, true), y = r.i32(42, true);
            if (x > 0 && y > 0) { o.dpiX = x * .0254; o.dpiY = y * .0254; }
            long imageSize = r.u32(34, true); r.check(pixels, imageSize);
            palette = r.u32(46, true);
        }
        require(planes == 1 && o.width > 0 && o.height > 0 && Set.of(1,2,4,8,16,24,32).contains(o.bits), "BMP: неверный заголовок");
        if (palette == 0 && o.bits <= 8) palette = 1L << o.bits;
        long masks = dib == 40 && (c == 3 || c == 6) ? (c == 6 ? 16 : 12) : 0;
        require(pixels >= 14 + dib + masks + palette * entrySize, "BMP: область палитры пересекает пиксели");
        if (c == 0 || c == 3 || c == 6) {
            long stride = ((o.width * o.bits + 31) / 32) * 4;
            require(stride <= Long.MAX_VALUE / o.height, "BMP: переполнение размера"); r.check(pixels, stride * o.height);
        }
        o.compression = switch ((int)c) {
            case 0 -> "BI_RGB"; case 1 -> "BI_RLE8"; case 2 -> "BI_RLE4"; case 3 -> "BI_BITFIELDS";
            case 4 -> "BI_JPEG"; case 5 -> "BI_PNG"; case 6 -> "BI_ALPHABITFIELDS"; default -> "BMP код " + c;
        };
        o.details = "DIB: " + dib + " байт; палитра: " + palette + " цветов (" + entrySize + " байт/элемент)";
    }

    private static void pcx(BinaryReader r, ImageInfo o) throws IOException {
        r.check(0, 128);
        int version = r.u8(1), encoding = r.u8(2), bits = r.u8(3), planes = r.u8(65);
        require(Set.of(0,2,3,4,5).contains(version) && encoding <= 1 && Set.of(1,2,4,8).contains(bits) && planes > 0, "PCX: неверный заголовок");
        o.width = r.u16(8, true) - r.u16(4, true) + 1; o.height = r.u16(10, true) - r.u16(6, true) + 1;
        o.bits = bits * planes; int line = r.u16(66, true);
        require(o.width > 0 && o.height > 0 && line >= (o.width * bits + 7) / 8, "PCX: неверная длина строки");
        o.dpiX = r.u16(12, true); o.dpiY = r.u16(14, true); o.compression = encoding == 1 ? "RLE" : "Без сжатия";
        require(r.size > 128, "PCX: нет пиксельных данных");
        if (encoding == 0) r.check(128, line * (long)planes * o.height);
        o.details = "Версия: " + version + "; плоскостей: " + planes + "; байт/строку/плоскость: " + line;
    }

    private static String tiffCompression(long value) {
        return switch ((int)value) {
            case 1 -> "Без сжатия"; case 2 -> "CCITT RLE"; case 3 -> "CCITT Group 3"; case 4 -> "CCITT Group 4";
            case 5 -> "LZW"; case 6 -> "JPEG (старый)"; case 7 -> "JPEG"; case 8,32946 -> "Deflate";
            case 32773 -> "PackBits"; default -> "TIFF код " + value;
        };
    }
    private static double value(Map<Integer,double[]> tags, int key, double fallback) {
        double[] values = tags.get(key); return values == null || values.length == 0 ? fallback : values[0];
    }
    private static void tiffCheck(BinaryReader r, long base, long limit, long p, long n) throws IOException {
        require(p >= 0 && n >= 0 && p <= limit - base && n <= limit - base - p, "TIFF: выход за границы блока"); r.check(base + p, n);
    }
    private static void tiff(BinaryReader r, ImageInfo o, long base, long limit, boolean exif) throws IOException {
        tiffCheck(r, base, limit, 0, 8);
        String sig = r.ascii(base, 2); require(sig.equals("II") || sig.equals("MM"), "TIFF: неверный порядок байтов");
        boolean le = sig.equals("II"); int magic = r.u16(base + 2, le);
        if (magic == 43) throw new UnsupportedOperationException("BigTIFF не поддерживается");
        require(magic == 42, "TIFF: неверная сигнатура");
        long off = r.u32(base + 4, le); Set<Long> seen = new HashSet<>(); int pages = 0;
        Set<Integer> wanted = Set.of(256,257,258,259,262,273,277,279,282,283,296,320,324,325);
        int[] sizes = {0,1,1,2,4,8,1,1,2,4,8,4,8};
        while (off != 0) {
            require(seen.add(off) && seen.size() <= 10000, "TIFF: цикл или слишком много IFD");
            tiffCheck(r, base, limit, off, 2); int count = r.u16(base + off, le);
            tiffCheck(r, base, limit, off + 2, 12L * count + 4);
            Map<Integer,double[]> tags = new HashMap<>();
            for (int i = 0; i < count; i++) {
                long entry = off + 2 + i * 12L;
                int tag = r.u16(base + entry, le), type = r.u16(base + entry + 2, le);
                long n = r.u32(base + entry + 4, le);
                if (type < 1 || type >= sizes.length) continue;
                long length = n * sizes[type]; long data = length <= 4 ? entry + 8 : r.u32(base + entry + 8, le);
                tiffCheck(r, base, limit, data, length);
                if (!wanted.contains(tag)) continue;
                require(n <= 1000000, "TIFF: слишком большой массив значений");
                if (tag == 320) { tags.put(tag, new double[]{n / 3.0}); continue; }
                require(type == 1 || type == 3 || type == 4 || type == 5, "TIFF: неподходящий тип тега " + tag);
                double[] values = new double[(int)n];
                for (int j = 0; j < n; j++) {
                    long p = base + data + (long)j * sizes[type];
                    if (type == 5) {
                        long numerator = r.u32(p, le), denominator = r.u32(p + 4, le);
                        require(denominator != 0, "TIFF: нулевой знаменатель"); values[j] = numerator / (double)denominator;
                    } else values[j] = type == 3 ? r.u16(p, le) : type == 4 ? r.u32(p, le) : r.u8(p);
                }
                tags.put(tag, values);
            }
            long width = (long)value(tags, 256, 0), height = (long)value(tags, 257, 0);
            if (!exif) {
                require(width > 0 && height > 0, "TIFF: нет размеров");
                for (int[] pair : new int[][]{{273,279},{324,325}}) {
                    double[] offsets = tags.get(pair[0]), lengths = tags.get(pair[1]);
                    if (offsets == null) continue;
                    require(lengths != null && offsets.length == lengths.length, "TIFF: неполные strips/tiles");
                    for (int j = 0; j < offsets.length; j++) tiffCheck(r, base, limit, (long)offsets[j], (long)lengths[j]);
                }
            }
            if (pages == 0) {
                o.width = width; o.height = height;
                double[] bits = tags.getOrDefault(258, new double[]{1}); int samples = (int)value(tags, 277, 1);
                double total = bits.length == 1 ? bits[0] * samples : Arrays.stream(bits).sum();
                require(total > 0 && total <= Integer.MAX_VALUE, "TIFF: неверная глубина цвета"); o.bits = (int)total;
                double unit = value(tags, 296, 2), factor = unit == 2 ? 1 : unit == 3 ? 2.54 : 0;
                o.dpiX = value(tags, 282, 0) * factor; o.dpiY = value(tags, 283, 0) * factor;
                o.compression = tiffCompression((long)value(tags, 259, 1));
                o.details = "Порядок байтов: " + sig + "; компонентов: " + samples
                    + (tags.containsKey(320) ? "; палитра: " + (long)value(tags, 320, 0) + " цветов" : "");
            }
            pages++; if (exif) break;
            off = r.u32(base + off + 2 + 12L * count, le);
        }
        require(pages > 0, "TIFF: нет IFD"); o.details += "; страниц: " + pages;
    }
}
