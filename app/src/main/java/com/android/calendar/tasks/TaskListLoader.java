package com.android.calendar.tasks;

import android.util.Log;
import com.android.calendar.ViewQueryLoader;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import android.os.CancellationSignal;

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

    private final ViewQueryLoader loader = new ViewQueryLoader();
    private final TaskStore store;
    private boolean active;

    TaskListLoader(TaskStore store) { this.store = store; }
    void start() { active = true; }

    void request(long selected, BiFunction<Long, CancellationSignal, List<T>> query, Consumer<Result<T>> apply,
            Runnable onError) {
        if (!active) return;
        loader.request(40, signal -> {
            List<TaskStore.TaskList> lists = store.visibleLists();
            long id = lists.stream().anyMatch(list -> list.id == selected) ? selected : 0;
            signal.throwIfCanceled();
            return new Result<>(lists, id, query.apply(id, signal));
        }, apply, error -> {
            Log.w("TaskListLoader", "Could not load local items", error);
            onError.run();
        });
    }

    void stop() { active = false; loader.cancel(); }
    void close() { active = false; loader.close(); }

}
