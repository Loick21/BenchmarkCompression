package com.zunix.convert;

import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

public class SimpleStreamConverter implements StreamConverter {

    @Override
    public void convert(Path input, Path result) {
        try(var inputStream = new ZstdCompressorInputStream(Files.newInputStream(input));
            var outputStream = new ZipOutputStream(Files.newOutputStream(result))) {
            IOUtils.copy(inputStream, outputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
