/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.io;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.lealone.cluster.config.Config;
import org.lealone.cluster.utils.ByteBufferUtil;

/**
 * When possible use {@link DataOutputStreamAndChannel} instead of this class, as it will
 * be more efficient. This class is only for situations where it cannot be used
 */
public class DataOutputStreamPlus extends OutputStream implements DataOutputPlus {
    protected final OutputStream out;
    private byte[] buf;

    public DataOutputStreamPlus(OutputStream out) {
        this.out = out;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    /*
    !! DataOutput methods below are copied from the implementation in Apache Harmony RandomAccessFile.
    */

    /**
     * Writes the entire contents of the byte array <code>buffer</code> to
     * this RandomAccessFile starting at the current file pointer.
     * 
     * @param buffer
     *            the buffer to be written.
     * 
     * @throws IOException
     *             If an error occurs trying to write to this RandomAccessFile.
     */
    @Override
    public void write(byte[] buffer) throws IOException {
        write(buffer, 0, buffer.length);
    }

    /**
     * Writes <code>count</code> bytes from the byte array <code>buffer</code>
     * starting at <code>offset</code> to this RandomAccessFile starting at
     * the current file pointer..
     * 
     * @param buffer
     *            the bytes to be written
     * @param offset
     *            offset in buffer to get bytes
     * @param count
     *            number of bytes in buffer to write
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             RandomAccessFile.
     * @throws IndexOutOfBoundsException
     *             If offset or count are outside of bounds.
     */
    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        out.write(buffer, offset, count);
    }

    /**
     * Writes the specified byte <code>oneByte</code> to this RandomAccessFile
     * starting at the current file pointer. Only the low order byte of
     * <code>oneByte</code> is written.
     * 
     * @param oneByte
     *            the byte to be written
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             RandomAccessFile.
     */
    @Override
    public void write(int oneByte) throws IOException {
        out.write(oneByte);
    }

    /**
     * Writes a boolean to this output stream.
     * 
     * @param val
     *            the boolean value to write to the OutputStream
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeBoolean(boolean val) throws IOException {
        write(val ? 1 : 0);
    }

    /**
     * Writes a 8-bit byte to this output stream.
     * 
     * @param val
     *            the byte value to write to the OutputStream
     * 
     * @throws java.io.IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeByte(int val) throws IOException {
        write(val & 0xFF);
    }

    /**
     * Writes the low order 8-bit bytes from a String to this output stream.
     * 
     * @param str
     *            the String containing the bytes to write to the OutputStream
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeBytes(String str) throws IOException {
        byte bytes[] = new byte[str.length()];
        for (int index = 0; index < str.length(); index++) {
            bytes[index] = (byte) (str.charAt(index) & 0xFF);
        }
        write(bytes);
    }

    /**
     * Writes the specified 16-bit character to the OutputStream. Only the lower
     * 2 bytes are written with the higher of the 2 bytes written first. This
     * represents the Unicode value of val.
     * 
     * @param val
     *            the character to be written
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeChar(int val) throws IOException {
        write((val >>> 8) & 0xFF);
        write((val >>> 0) & 0xFF);
    }

    /**
     * Writes the specified 16-bit characters contained in str to the
     * OutputStream. Only the lower 2 bytes of each character are written with
     * the higher of the 2 bytes written first. This represents the Unicode
     * value of each character in str.
     * 
     * @param str
     *            the String whose characters are to be written.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeChars(String str) throws IOException {
        byte newBytes[] = new byte[str.length() * 2];
        for (int index = 0; index < str.length(); index++) {
            int newIndex = index == 0 ? index : index * 2;
            newBytes[newIndex] = (byte) ((str.charAt(index) >> 8) & 0xFF);
            newBytes[newIndex + 1] = (byte) (str.charAt(index) & 0xFF);
        }
        write(newBytes);
    }

    /**
     * Writes a 64-bit double to this output stream. The resulting output is the
     * 8 bytes resulting from calling Double.doubleToLongBits().
     * 
     * @param val
     *            the double to be written.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeDouble(double val) throws IOException {
        writeLong(Double.doubleToLongBits(val));
    }

    /**
     * Writes a 32-bit float to this output stream. The resulting output is the
     * 4 bytes resulting from calling Float.floatToIntBits().
     * 
     * @param val
     *            the float to be written.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeFloat(float val) throws IOException {
        writeInt(Float.floatToIntBits(val));
    }

    /**
     * Writes a 32-bit int to this output stream. The resulting output is the 4
     * bytes, highest order first, of val.
     * 
     * @param val
     *            the int to be written.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public void writeInt(int val) throws IOException {
        write((val >>> 24) & 0xFF);
        write((val >>> 16) & 0xFF);
        write((val >>> 8) & 0xFF);
        write((val >>> 0) & 0xFF);
    }

    /**
     * Writes a 64-bit long to this output stream. The resulting output is the 8
     * bytes, highest order first, of val.
     * 
     * @param val
     *            the long to be written.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public void writeLong(long val) throws IOException {
        write((int) (val >>> 56) & 0xFF);
        write((int) (val >>> 48) & 0xFF);
        write((int) (val >>> 40) & 0xFF);
        write((int) (val >>> 32) & 0xFF);
        write((int) (val >>> 24) & 0xFF);
        write((int) (val >>> 16) & 0xFF);
        write((int) (val >>> 8) & 0xFF);
        write((int) (val >>> 0) & 0xFF);
    }

    /**
     * Writes the specified 16-bit short to the OutputStream. Only the lower 2
     * bytes are written with the higher of the 2 bytes written first.
     * 
     * @param val
     *            the short to be written
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public void writeShort(int val) throws IOException {
        writeChar(val);
    }

    /**
     * Writes the specified String out in UTF format.
     * 
     * @param str
     *            the String to be written in UTF format.
     * 
     * @throws IOException
     *             If an error occurs attempting to write to this
     *             DataOutputStream.
     */
    @Override
    public final void writeUTF(String str) throws IOException {
        int utfCount = 0, length = str.length();
        for (int i = 0; i < length; i++) {
            int charValue = str.charAt(i);
            if (charValue > 0 && charValue <= 127) {
                utfCount++;
            } else if (charValue <= 2047) {
                utfCount += 2;
            } else {
                utfCount += 3;
            }
        }
        if (utfCount > 65535) {
            throw new UTFDataFormatException(); //$NON-NLS-1$
        }
        byte utfBytes[] = new byte[utfCount + 2];
        int utfIndex = 2;
        for (int i = 0; i < length; i++) {
            int charValue = str.charAt(i);
            if (charValue > 0 && charValue <= 127) {
                utfBytes[utfIndex++] = (byte) charValue;
            } else if (charValue <= 2047) {
                utfBytes[utfIndex++] = (byte) (0xc0 | (0x1f & (charValue >> 6)));
                utfBytes[utfIndex++] = (byte) (0x80 | (0x3f & charValue));
            } else {
                utfBytes[utfIndex++] = (byte) (0xe0 | (0x0f & (charValue >> 12)));
                utfBytes[utfIndex++] = (byte) (0x80 | (0x3f & (charValue >> 6)));
                utfBytes[utfIndex++] = (byte) (0x80 | (0x3f & charValue));
            }
        }
        utfBytes[0] = (byte) (utfCount >> 8);
        utfBytes[1] = (byte) utfCount;
        write(utfBytes);
    }

    @Override
    public synchronized void write(ByteBuffer buffer) throws IOException {
        int len = buffer.remaining();
        if (len < 16) {
            int offset = buffer.position();
            for (int i = 0; i < len; i++)
                write(buffer.get(i + offset));
            return;
        }

        byte[] buf = this.buf;
        if (buf == null)
            this.buf = buf = new byte[256];

        int offset = 0;
        while (len > 0) {
            int sublen = Math.min(buf.length, len);
            ByteBufferUtil.arrayCopy(buffer, buffer.position() + offset, buf, 0, sublen);
            write(buf, 0, sublen);
            offset += sublen;
            len -= sublen;
        }
    }

    private static int MAX_BUFFER_SIZE = Integer.parseInt(Config.getProperty(
            "data_output_stream_plus_temp_buffer_size", "8192"));

    /*
     * Factored out into separate method to create more flexibility around inlining
     */
    protected static byte[] retrieveTemporaryBuffer(int minSize) {
        byte[] bytes = tempBuffer.get();
        if (bytes.length < Math.min(minSize, MAX_BUFFER_SIZE)) {
            // increase in powers of 2, to avoid wasted repeat allocations
            bytes = new byte[Math.min(MAX_BUFFER_SIZE, 2 * Integer.highestOneBit(minSize))];
            tempBuffer.set(bytes);
        }
        return bytes;
    }

    private static final ThreadLocal<byte[]> tempBuffer = new ThreadLocal<byte[]>() {
        @Override
        public byte[] initialValue() {
            return new byte[16];
        }
    };

    public WritableByteChannel newDefaultChannel() {
        return new WritableByteChannel() {

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                int toWrite = src.remaining();

                if (src.hasArray()) {
                    DataOutputStreamPlus.this.write(src.array(), src.arrayOffset() + src.position(), src.remaining());
                    src.position(src.limit());
                    return toWrite;
                }

                if (toWrite < 16) {
                    int offset = src.position();
                    for (int i = 0; i < toWrite; i++)
                        DataOutputStreamPlus.this.write(src.get(i + offset));
                    src.position(src.limit());
                    return toWrite;
                }

                byte[] buf = retrieveTemporaryBuffer(toWrite);

                int totalWritten = 0;
                while (totalWritten < toWrite) {
                    int toWriteThisTime = Math.min(buf.length, toWrite - totalWritten);

                    ByteBufferUtil.arrayCopy(src, src.position() + totalWritten, buf, 0, toWriteThisTime);

                    DataOutputStreamPlus.this.write(buf, 0, toWriteThisTime);

                    totalWritten += toWriteThisTime;
                }

                src.position(src.limit());
                return totalWritten;
            }

        };
    }
}
