package io.vanillabp.camunda8.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class HashCodeInputStream extends InputStream {

    private final InputStream delegate;
    
    private int hashCode;
    
    private int totalHashCode;

    private int markedHashCode;

    public HashCodeInputStream(
            final InputStream delegate) {
        
        this(delegate, 0);
        
    }

    public HashCodeInputStream(
            final InputStream delegate,
            final int previousHashCode) {
        
        this.delegate = delegate;
        this.totalHashCode = previousHashCode;
        
    }
    
    @Override
    public int read() throws IOException {
        
        final int read = delegate.read();
        
        hashCode = 31 * hashCode + read;
        totalHashCode = 31 * totalHashCode + read;
        
        return read;
        
    }
    
    @Override
    public int hashCode() {
        
        return hashCode;
        
    }

    public int getTotalHashCode() {

        return totalHashCode;

    }

    @Override
    public long skip(long n) throws IOException {

        return delegate.skip(n);

    }

    @Override
    public int available() throws IOException {

        return delegate.available();

    }

    @Override
    public void close() throws IOException {

        delegate.close();

    }

    @Override
    public void mark(int readlimit) {

        markedHashCode = hashCode;
        delegate.mark(readlimit);

    }

    @Override
    public void reset() throws IOException {

        hashCode = markedHashCode;
        delegate.reset();

    }

    @Override
    public boolean markSupported() {

        return delegate.markSupported();

    }

    @Override
    public long transferTo(OutputStream out) throws IOException {

        return delegate.transferTo(out);

    }

}