package ru.matsukov.raster;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Запуск отдельно от тестов: синтетический дисковый тест 600 × 3.5 МиБ. */
public final class StressCheck {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("raster-stress-");
        byte[] jpeg=Files.readAllBytes(Path.of("samples/sample.jpg"));
        try {
            for(int i=0;i<600;i++) {
                try(RandomAccessFile f=new RandomAccessFile(dir.resolve(i+".jpg").toFile(),"rw")) {
                    f.write(jpeg,0,jpeg.length-2);f.setLength(3_500_000);f.seek(f.length()-2);f.write(255);f.write(217);
                }
            }
            ScanSession scan=new ScanSession(List.of(dir),8);scan.start();int rows=0,errors=0;long read=0;
            long deadline=System.nanoTime()+120_000_000_000L;
            while(!scan.finished.get()||!scan.results.isEmpty()) {
                ImageInfo r=scan.results.poll(100,TimeUnit.MILLISECONDS);
                if(r!=null){rows++;read+=r.bytesRead;if(!r.status.equals("OK"))errors++;}
                if(System.nanoTime()>deadline){scan.cancel();throw new AssertionError("Timeout");}
            }
            System.out.printf(Locale.ROOT,"Synthetic sparse JPEG files: %d; logical bytes: %d; read bytes: %d; errors: %d; elapsed: %.3f s%n",rows,600L*3_500_000,read,errors,scan.seconds());
            if(rows!=600||errors!=0||read>=600L*75000)throw new AssertionError("Stress check failed");
        } finally {
            try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);}Files.delete(dir);
        }
    }
}
