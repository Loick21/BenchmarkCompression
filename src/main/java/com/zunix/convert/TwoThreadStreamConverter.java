package com.zunix.convert;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.StopWatch;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TwoThreadStreamConverter implements StreamConverter {

    private BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();


    @SneakyThrows
    @Override
    public void convert(Path input, Path result) {
        byte[] poisonPill = new byte[1];
        ByteProducer producer = new ByteProducer(this.queue, input, poisonPill);
        ByteConsumer consumer = new ByteConsumer(this.queue, result, poisonPill);

        CompletableFuture.runAsync(producer);
        CompletableFuture.runAsync(consumer);

    }

    @RequiredArgsConstructor
    class ByteProducer implements Runnable {

        private final BlockingQueue<byte[]> queue;
        private final Path path;
        private final byte[] poisonPill;
        private Logger log = Logger.getLogger("ByteConsumer");

        @SneakyThrows
        @Override
        public void run() {
            try (var inputStream = new ZstdCompressorInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[4096];
                int read = 0;
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();
                while ((read = inputStream.read(buffer)) != -1) {
                    this.queue.put(buffer);
                }
                this.queue.add(poisonPill);
                stopWatch.stop();
                log.info("Reading in buffer took : " + TimeUnit.MILLISECONDS.convert(stopWatch.getNanoTime(), TimeUnit.NANOSECONDS));
            }
        }
    }


    class ByteConsumer implements Runnable {
        private final BlockingQueue<byte[]> queue;
        private final Path path;
        private final byte[] poisonPill;
        private ZipOutputStream outputStream;
        private Logger log = Logger.getLogger("ByteConsumer");

        public ByteConsumer(BlockingQueue<byte[]> queue, Path path, byte[] poisonPill) throws IOException {
            this.queue = queue;
            this.path = path;
            this.poisonPill = poisonPill;
            this.outputStream = new ZipOutputStream(Files.newOutputStream(path));
            ZipEntry zipEntry = new ZipEntry(String.valueOf(path.getFileName()).concat(".json"));
            this.outputStream.putNextEntry(zipEntry);
        }

        @SneakyThrows
        @Override
        public void run() {
            byte[] buffer = queue.poll();
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            while (buffer != null && buffer != poisonPill) {
                outputStream.write(buffer);
            }
            stopWatch.stop();
            log.info("Writting buffer took : " + TimeUnit.MILLISECONDS.convert(stopWatch.getNanoTime(), TimeUnit.NANOSECONDS));
        }

        void close() throws IOException {
            outputStream.close();
        }
    }
}
