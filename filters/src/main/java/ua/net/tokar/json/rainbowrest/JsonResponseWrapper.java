package ua.net.tokar.json.rainbowrest;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

class JsonResponseWrapper extends HttpServletResponseWrapper {
    // RFC 8259 (section 11) specifies that 'charset' parameter for media
    // type 'application/json' has no effect and JSON text MUST be encoded
    // using UTF-8 (section 8.1)
    private static final String DEFAULT_CHARSET = StandardCharsets.UTF_8.name();

    private String charset = DEFAULT_CHARSET;
    private final ByteArrayOutputStream capture;
    private ServletOutputStream output;
    private PrintWriter writer;

    JsonResponseWrapper(ServletResponse response) {
        super((HttpServletResponse)response);
        super.setCharacterEncoding( charset );
        capture = new ByteArrayOutputStream(response.getBufferSize());
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException(
                    "getWriter() has already been called on this response.");
        }

        if (output == null) {
            output = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    capture.write(b);
                }

                @Override
                public void flush() throws IOException {
                    capture.flush();
                }

                @Override
                public void close() throws IOException {
                    capture.close();
                }

                @Override
                public boolean isReady() {
                    return false;
                }

                @Override
                public void setWriteListener(WriteListener arg0) {
                }
            };
        }

        return output;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (output != null) {
            throw new IllegalStateException(
                    "getOutputStream() has already been called on this response.");
        }

        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(capture,
                    getCharacterEncoding()));
        }

        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        super.flushBuffer();

        if (writer != null) {
            writer.flush();
        } else if (output != null) {
            output.flush();
        }
    }

    @Override
    public void setCharacterEncoding(String charset) {
        // for backward compatibility use non-default charset if set explicitly
        super.setCharacterEncoding(charset);
        this.charset = charset != null ? charset : DEFAULT_CHARSET;
    }

    private byte[] getCaptureAsBytes() throws IOException {
        if (writer != null) {
            writer.close();
        } else if (output != null) {
            output.close();
        }

        return capture.toByteArray();
    }

    String getCaptureAsString() throws IOException {
        return new String(getCaptureAsBytes(), charset);
    }
}
