/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.util;

import edu.umd.cs.findbugs.annotations.CreatesObligation;

import hudson.Util;
import jenkins.util.io.LinesStream;

import java.nio.file.Files;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents a text file.
 *
 * Provides convenience methods for reading and writing to it.
 *
 * @author Kohsuke Kawaguchi
 */
public class TextFile {

    public final @NonNull File file;

    public TextFile(@NonNull File file) {
        this.file = file;
    }

    public boolean exists() {
        return file.exists();
    }

    public void delete() {
        file.delete();
    }

    /**
     * Reads the entire contents and returns it.
     */
    public String read() throws IOException {
        StringWriter out = new StringWriter();
        PrintWriter w = new PrintWriter(out);
        try (BufferedReader in = Files.newBufferedReader(Util.fileToPath(file), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null)
                w.println(line);
        } catch (Exception e) {
            throw new IOException("Failed to fully read " + file, e);
        }
        return out.toString();
    }

    /**
     * @throws RuntimeException in the case of {@link IOException} in {@link #linesStream()}
     * @deprecated This method does not properly propagate errors and may lead to file descriptor leaks
     *             if the collection is not fully iterated. Use {@link #linesStream()} instead.
     */
    @Deprecated
    public @NonNull Iterable<String> lines() {
        try {
            return linesStream();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Creates a new {@link jenkins.util.io.LinesStream} of the file.
     * <p>
     * Note: The caller is responsible for closing the returned
     * {@code LinesStream}.
     * @throws IOException if the file cannot be converted to a
     * {@link java.nio.file.Path} or if the file cannot be opened for reading
     * @since 2.111
     */
    @CreatesObligation
    public @NonNull LinesStream linesStream() throws IOException {
        return new LinesStream(Util.fileToPath(file));
    }

    /**
     * Overwrites the file by the given string.
     */
    public void write(String text) throws IOException {
        file.getParentFile().mkdirs();
        try (AtomicFileWriter w = new AtomicFileWriter(file)) {
            try {
                w.write(text);
                w.commit();
            } finally {
                w.abort();
            }
        }
    }

    /**
     * Reads the first N characters or until we hit EOF.
     */
    public @NonNull String head(int numChars) throws IOException {
        char[] buf = new char[numChars];
        int read = 0;
        try (Reader r = new FileReader(file)) {
            while (read<numChars) {
                int d = r.read(buf,read,buf.length-read);
                if (d<0)
                    break;
                read += d;
            }

            return new String(buf,0,read);
        }
    }

    /**
     * Efficiently reads the last N characters (or shorter, if the whole file is shorter than that.)
     *
     * <p>
     * This method first tries to just read the tail section of the file to get the necessary chars.
     * To handle multi-byte variable length encoding (such as UTF-8), we read a larger than
     * necessary chunk.
     *
     * <p>
     * Some multi-byte encoding, such as Shift-JIS (http://en.wikipedia.org/wiki/Shift_JIS) doesn't
     * allow the first byte and the second byte of a single char to be unambiguously identified,
     * so it is possible that we end up decoding incorrectly if we start reading in the middle of a multi-byte
     * character. All the CJK multi-byte encodings that I know of are self-correcting; as they are ASCII-compatible,
     * any ASCII characters or control characters will bring the decoding back in sync, so the worst
     * case we just have some garbage in the beginning that needs to be discarded. To accommodate this,
     * we read additional 1024 bytes.
     *
     * <p>
     * Other encodings, such as UTF-8, are better in that the character boundary is unambiguous,
     * so there can be at most one garbage char. For dealing with UTF-16 and UTF-32, we read at
     * 4 bytes boundary (all the constants and multipliers are multiples of 4.)
     *
     * <p>
     * Note that it is possible to construct a contrived input that fools this algorithm, and in this method
     * we are willing to live with a small possibility of that to avoid reading the whole text. In practice,
     * such an input is very unlikely.
     *
     * <p>
     * So all in all, this algorithm should work decently, and it works quite efficiently on a large text.
     */
    public @NonNull String fastTail(int numChars, Charset cs) throws IOException {

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            // err on the safe side and assume each char occupies 4 bytes
            // additional 1024 byte margin is to bring us back in sync in case we started reading from non-char boundary.
            long pos = Math.max(0, len - (numChars * 4 + 1024));
            raf.seek(pos);

            byte[] tail = new byte[(int) (len - pos)];
            raf.readFully(tail);

            String tails = cs.decode(java.nio.ByteBuffer.wrap(tail)).toString();

            return tails.substring(Math.max(0, tails.length() - numChars));
        }
    }

    /**
     * Uses the platform default encoding.
     */
    public @NonNull String fastTail(int numChars) throws IOException {
        return fastTail(numChars,Charset.defaultCharset());
    }


    public String readTrim() throws IOException {
        return read().trim();
    }

    @Override
    public String toString() {
        return file.toString();
    }
}
