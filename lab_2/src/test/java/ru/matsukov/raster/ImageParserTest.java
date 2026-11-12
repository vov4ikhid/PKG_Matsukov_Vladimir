package ru.matsukov.raster;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageParserTest {
    @TempDir Path temp;
    static Stream<Arguments> fixtures() {
        List<Arguments> list = new ArrayList<>();
        for (String ext : List.of("jpg","png","gif","tif","bmp","pcx"))
            list.add(Arguments.of(Path.of("samples/sample." + ext), ext.equals("gif") ? 2 : 24));
        for (String mode : List.of("1","L","P","RGB","RGBA"))
            for (String ext : List.of("png","tiff","bmp")) {
                if (mode.equals("RGBA") && ext.equals("bmp")) continue;
                list.add(Arguments.of(Path.of("test-data/" + mode + "." + ext), switch(mode) {case "1" -> 1; case "L","P" -> 8; case "RGB" -> 24; default -> 32;}));
            }
        for (String name : List.of("raw.tif","tiff_lzw.tif","packbits.tif","tiff_adobe_deflate.tif","multi.tif","progressive.jpg")) list.add(Arguments.of(Path.of("test-data",name),24));
        return list.stream();
    }
    @ParameterizedTest @MethodSource("fixtures") void readsFormats(Path path, int bits) {
        ImageInfo r = ImageParser.inspect(path); assertEquals("OK",r.status,r.description());
        assertEquals(37,r.width); assertEquals(23,r.height); assertEquals(bits,r.bits);
    }
    @Test void resolutions() {
        for (String ext : List.of("jpg","png","tif","bmp")) {
            ImageInfo r = ImageParser.inspect(Path.of("samples/sample."+ext)); assertEquals(150,r.dpiX,.03); assertEquals(300,r.dpiY,.03);
        }
        assertEquals(0,ImageParser.inspect(Path.of("samples/sample.gif")).dpiX);
        assertEquals(100,ImageParser.inspect(Path.of("samples/sample.pcx")).dpiX);
    }
    private Path write(String name, byte[] bytes) throws IOException { return Files.write(temp.resolve(name),bytes); }
    private byte[] sample(String ext) throws IOException { return Files.readAllBytes(Path.of("samples/sample."+ext)); }
    private void damaged(String name,byte[] bytes) throws IOException {
        ImageInfo r = ImageParser.inspect(write(name,bytes)); assertEquals("Файл повреждён",r.status,r.description());
    }
    @Test void missingEndMarkers() throws IOException {
        byte[] png=sample("png"),jpg=sample("jpg"),gif=sample("gif");
        damaged("no-iend.png",Arrays.copyOf(png,png.length-12));
        damaged("no-eoi.jpg",Arrays.copyOf(jpg,jpg.length-2));
        damaged("no-trailer.gif",Arrays.copyOf(gif,gif.length-1));
    }
    @Test void signaturesAndTruncation() throws IOException {
        damaged("empty.jpg",new byte[0]); damaged("fake.jpg","this is not JPEG".getBytes());
        for(String ext:List.of("jpg","png","tif","bmp","pcx","gif")) {
            byte[] b=sample(ext); for(int n:new int[]{1,2,8,12,20}) damaged(ext+n,Arrays.copyOf(b,n));
        }
        ImageInfo disguised=ImageParser.inspect(write("renamed.jpg",sample("png")));
        assertEquals("OK",disguised.status); assertEquals("PNG",disguised.format); assertTrue(disguised.details.contains("расширение"));
    }
    private byte[] bigEndianTiff() {
        ByteBuffer b=ByteBuffer.allocate(180).order(ByteOrder.BIG_ENDIAN);b.put((byte)'M').put((byte)'M').putShort((short)42).putInt(8).putShort((short)8);
        int[][] entries={{256,3,1,37},{257,3,1,23},{258,3,3,110},{277,3,1,3},{259,3,1,1},{282,5,1,116},{283,5,1,124},{296,3,1,3}};
        for(int[] e:entries){b.putShort((short)e[0]).putShort((short)e[1]).putInt(e[2]);if(e[1]==3&&e[2]==1)b.putShort((short)e[3]).putShort((short)0);else b.putInt(e[3]);}
        b.putInt(0);b.position(110);b.putShort((short)8).putShort((short)8).putShort((short)8).putInt(100).putInt(1).putInt(200).putInt(1);return b.array();
    }
    @Test void bigEndianRationalsAndCycles() throws IOException {
        byte[] bytes=bigEndianTiff();ImageInfo r=ImageParser.inspect(write("be.tif",bytes));
        assertEquals("OK",r.status,r.description());assertEquals(24,r.bits);assertEquals(254,r.dpiX);assertEquals(508,r.dpiY);
        ByteBuffer.wrap(bytes).putInt(106,8);damaged("cycle.tif",bytes);
        ByteBuffer.wrap(bytes).putInt(106,0).putInt(120,0);damaged("denominator.tif",bytes);
    }
    @Test void exifOverridesJfif() throws IOException {
        byte[] tiff=bigEndianTiff(),jpg=sample("jpg");ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(jpg,0,2);
        DataOutputStream d=new DataOutputStream(out);d.writeShort(0xffe1);d.writeShort(tiff.length+8);d.writeBytes("Exif\0\0");d.write(tiff);d.write(jpg,2,jpg.length-2);
        ImageInfo r=ImageParser.inspect(write("exif.jpg",out.toByteArray()));assertEquals("OK",r.status,r.description());assertEquals(254,r.dpiX);assertEquals(508,r.dpiY);
    }
    @Test void bmpDeclaredSizeAndPixelBounds() throws IOException {
        byte[] b=sample("bmp");ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(2,b.length+10);damaged("size.bmp",b);
        b=sample("bmp");ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(22,100000);damaged("pixels.bmp",b);
    }
    @Test void unsupportedBigTiffAndAccessError() throws IOException {
        byte[] b=bigEndianTiff();ByteBuffer.wrap(b).putShort(2,(short)43);
        assertEquals("Не поддерживается",ImageParser.inspect(write("big.tif",b)).status);
        assertEquals("Ошибка доступа",ImageParser.inspect(temp.resolve("missing.jpg")).status);
    }
    @Test void sparseLargeFileReadsOnlySmallParts() throws IOException {
        // Синтетический тест ввода-вывода, НЕ валидатор сжатого JPEG-потока.
        Path file=temp.resolve("large.jpg");byte[] jpg=sample("jpg");
        try(RandomAccessFile f=new RandomAccessFile(file.toFile(),"rw")){f.write(jpg,0,jpg.length-2);f.setLength(4L*1024*1024);f.seek(f.length()-2);f.write(255);f.write(217);}
        ImageInfo r=ImageParser.inspect(file);assertEquals("OK",r.status,r.description());assertTrue(r.bytesRead<75000,r.description());
    }
    @Test void randomizedInputsNeverEscapeAsRuntimeFailure() throws IOException {
        Random random=new Random(42);
        for(int i=0;i<300;i++){byte[] b=new byte[random.nextInt(500)];random.nextBytes(b);ImageInfo r=ImageParser.inspect(write("fuzz.bin",b));assertNotNull(r.status);}
    }
    @Test void csvEscapesAndRetainsUnicode() throws IOException {
        ImageInfo r=new ImageInfo(Path.of("=formula;имя.png"));r.details="строка\n\"два\"";
        Path csv=temp.resolve("out.csv");CsvExporter.write(csv,List.of(r));String text=Files.readString(csv);
        assertTrue(text.startsWith("\ufeff"));assertTrue(text.contains("' =formula".replace("' ","'")));assertTrue(text.contains("\"\"два\"\""));assertTrue(text.contains("строка\n"));
    }
    @Test void boundedConcurrentScanAndCancellation() throws Exception {
        Path dir=Files.createDirectory(temp.resolve("scan"));byte[] png=sample("png");
        for(int i=0;i<620;i++)Files.write(dir.resolve(i+".png"),png);
        ScanSession scan=new ScanSession(List.of(dir),4);scan.start();
        int rows=0;long deadline=System.nanoTime()+20_000_000_000L;
        while(!scan.finished.get()||!scan.results.isEmpty()) {
            ImageInfo r=scan.results.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);if(r!=null){assertEquals("OK",r.status);rows++;}
            assertTrue(System.nanoTime()<deadline,"scan timeout");
        }
        assertEquals(620,rows);assertEquals(620,scan.completed.get());
        ScanSession cancelled=new ScanSession(List.of(dir),2);cancelled.start();cancelled.cancel();
        while(!cancelled.finished.get()){Thread.sleep(10);assertTrue(System.nanoTime()<deadline);}
        assertTrue(cancelled.cancelled.get());
    }
}
