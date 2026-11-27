package com.android.calendar;

import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/** View-scoped, latest-request-wins query runner. All lifecycle methods run on the main thread. */
public final class ViewQueryLoader implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Runnable pending;
    private Future<?> running;
    private CancellationSignal cancellation;
    private int generation;
    private boolean closed;

    public <T> void request(long delay, Function<CancellationSignal, T> query,
            Consumer<T> apply, Consumer<RuntimeException> onError) {
        if (closed) return;
        cancel();
        int ticket = generation;
        CancellationSignal signal = new CancellationSignal();
        cancellation = signal;
        pending = () -> {
            pending = null;
            running = worker.submit(() -> {
                try {
                    signal.throwIfCanceled();
                    T result = query.apply(signal);
                    signal.throwIfCanceled();
                    main.post(() -> {
                        if (!closed && ticket == generation) apply.accept(result);
                    });
                } catch (OperationCanceledException ignored) {
                    // A newer request or a stopped view owns the UI.
                } catch (RuntimeException error) {
                    if (signal.isCanceled() || Thread.currentThread().isInterrupted()) return;
                    main.post(() -> {
                        if (!closed && ticket == generation) onError.accept(error);
                    });
                }
            });
        };
        if (delay > 0) main.postDelayed(pending, delay);
        else pending.run();
    }

    public void cancel() {
        generation++;
        if (pending != null) main.removeCallbacks(pending);
        if (cancellation != null) cancellation.cancel();
        if (running != null) running.cancel(true);
        pending = null;
        running = null;
        cancellation = null;
        main.removeCallbacksAndMessages(null);
    }

    @Override public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
