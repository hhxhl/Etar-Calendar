package com.android.calendar.tasks;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/** View-scoped background loader; only the most recent request can update a view. */
final class TaskListLoader<T> {
    static final class Result<T> {
        final List<TaskStore.TaskList> lists;
        final long selected;
        final List<T> items;
        Result(List<TaskStore.TaskList> lists, long selected, List<T> items) {
            this.lists = lists;
            this.selected = selected;
            this.items = items;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TaskStore store;
    private Runnable pending;
    private Future<?> running;
    private int generation;
    private boolean active;

    TaskListLoader(TaskStore store) { this.store = store; }
    void start() { active = true; }

    void request(long selected, LongFunction<List<T>> query, Consumer<Result<T>> apply,
            Runnable onError) {
        if (!active) return;
        cancel();
        final int ticket = generation;
        pending = () -> {
            pending = null;
            running = worker.submit(() -> {
                try {
                    List<TaskStore.TaskList> lists = store.visibleLists();
                    long id = lists.stream().anyMatch(list -> list.id == selected) ? selected : 0;
                    Result<T> result = new Result<>(lists, id, query.apply(id));
                    main.post(() -> {
                        if (active && ticket == generation) apply.accept(result);
                    });
                } catch (RuntimeException error) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Log.w("TaskListLoader", "Could not load local items", error);
                    main.post(() -> {
                        if (active && ticket == generation) onError.run();
                    });
                }
            });
        };
        main.postDelayed(pending, 40);
    }

    private void cancel() {
        generation++;
        if (pending != null) main.removeCallbacks(pending);
        if (running != null) running.cancel(true);
        pending = null;
        running = null;
    }

    void stop() { active = false; cancel(); }
    void close() {
        stop();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
