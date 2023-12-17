package ru.danilspirin;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

public interface AbstractingMethod {
    void process(InputStream input, OutputStream output) throws FileNotFoundException;

}
