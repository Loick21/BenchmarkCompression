package com.zunix.convert;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

public interface StreamConverter {

    void convert (Path input, Path result);


}
