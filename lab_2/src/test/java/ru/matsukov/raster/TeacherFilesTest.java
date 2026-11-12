package ru.matsukov.raster;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Expected geometry independently checked against Pillow; parser itself uses no image library. */
class TeacherFilesTest {
    static Stream<Arguments> teacherFiles() {
        return Stream.of(
            Arguments.of("Fig1.15(a).bmp", 512, 480),
            Arguments.of("Fig1.15(b).BMP", 1184, 496),
            Arguments.of("Fig10.15(a).BMP", 360, 414),
            Arguments.of("Fig10.29(a).bmp", 400, 480),
            Arguments.of("Fig2.19(a).BMP", 1024, 1024),
            Arguments.of("Fig2.22(a).BMP", 300, 300),
            Arguments.of("Fig3.15(a)1top.bmp", 500, 500),
            Arguments.of("Fig3.15(a)2.bmp", 500, 500),
            Arguments.of("Fig3.15(a)3.bmp", 500, 500),
            Arguments.of("Fig3.15(a)4.bmp", 500, 500),
            Arguments.of("Fig3.27(a).BMP", 370, 457),
            Arguments.of("Fig3.35(a).bmp", 500, 500),
            Arguments.of("Fig3.37(a).bmp", 455, 440),
            Arguments.of("Fig3.45(a).bmp", 937, 932),
            Arguments.of("Fig4.19(a).bmp", 508, 444),
            Arguments.of("Fig4.20(a).BMP", 732, 1028),
            Arguments.of("Fig4.28(a).BMP", 464, 538),
            Arguments.of("Fig5.04(i).bmp", 256, 256),
            Arguments.of("Fig5.08(a).bmp", 464, 448),
            Arguments.of("Fig5.08(b).bmp", 464, 448),
            Arguments.of("Fig5.10(a).bmp", 455, 440),
            Arguments.of("Fig9.11(a).bmp", 315, 238),
            Arguments.of("Fig9.14(a).bmp", 221, 269),
            Arguments.of("Fig9.16(a).bmp", 512, 512));
    }
    @ParameterizedTest @MethodSource("teacherFiles")
    void readsTeacherBmp(String name, int width, int height) {
        ImageInfo result = ImageParser.inspect(Path.of("test-data", "teacher", name));
        assertEquals("OK", result.status, result.description());
        assertEquals("BMP", result.format);
        assertEquals(width, result.width);
        assertEquals(height, result.height);
        assertEquals(8, result.bits);
        assertEquals(0, result.dpiX);
        assertEquals(0, result.dpiY);
        assertEquals("BI_RGB", result.compression);
        assertTrue(result.details.contains("256 цветов"));
    }
}
