package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class IncomingImageReaderInstrumentedTest {
    private File mPackageDir;
    private IncomingImageReader mReader;

    @Before
    public void setUp() {
        File cache = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir();
        mPackageDir = new File(cache, "fd-mutate-input-test");
        assertTrue(mPackageDir.mkdirs() || mPackageDir.isDirectory());
        mReader = new IncomingImageReader(Logger.getLogger("IncomingImageReaderTest"));
    }

    @After
    public void tearDown() {
        File[] files = mPackageDir.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(mPackageDir.delete() || !mPackageDir.exists());
    }

    @Test
    public void decodesValidWebpAndDeletesIncomingFile() throws Exception {
        byte[] webp = createWebp();
        PipeInput pipe = pipeFrom(webp);
        IncomingImageReader.ReadResult result = mReader.read(pipe.readEnd, mPackageDir,
                "valid", "com.example.target", "main");
        pipe.await();

        assertTrue(result.valid);
        assertNotNull(result.bitmap);
        result.bitmap.recycle();
        assertNoIncomingFiles();
    }

    @Test
    public void rejectsEmptyTruncatedCorruptAndOversizedStreams() throws Exception {
        assertRejected(new byte[0], "empty");

        byte[] webp = createWebp();
        assertRejected(Arrays.copyOf(webp, Math.max(1, webp.length / 2)), "truncated");
        assertRejected(new byte[] {1, 2, 3, 4, 5}, "corrupt");
        assertRejected(new byte[GmConstants.MAX_IMAGE_FILE_SIZE_BYTES + 1], "oversized");
    }

    private void assertRejected(byte[] bytes, String requestId) throws Exception {
        PipeInput pipe = pipeFrom(bytes);
        IncomingImageReader.ReadResult result = mReader.read(pipe.readEnd, mPackageDir,
                requestId, "com.example.target", "main");
        pipe.await();

        assertFalse(result.valid);
        assertNoIncomingFiles();
    }

    private void assertNoIncomingFiles() {
        File[] incoming = mPackageDir.listFiles(file ->
                file.getName().startsWith(".incoming-"));
        assertTrue(incoming == null || incoming.length == 0);
    }

    private static byte[] createWebp() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 80, output));
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static PipeInput pipeFrom(byte[] bytes) throws IOException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "fd-input-test-writer");
        writer.setDaemon(true);
        writer.start();
        return new PipeInput(pipe[0], writer, failure);
    }

    private static final class PipeInput {
        final ParcelFileDescriptor readEnd;
        final Thread writer;
        final AtomicReference<Throwable> failure;

        PipeInput(ParcelFileDescriptor readEnd, Thread writer,
                  AtomicReference<Throwable> failure) {
            this.readEnd = readEnd;
            this.writer = writer;
            this.failure = failure;
        }

        void await() throws Exception {
            writer.join(5_000L);
            assertFalse("writer leaked", writer.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
