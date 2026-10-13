package com.example.photoviewer;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 수업 자료의 AsyncTask와 같은 모양(doInBackground / onPostExecute)으로 쓰기 위한 클래스.
 * AsyncTask는 API 30부터 deprecated 되어서 ExecutorService + Handler로 같은 동작을 구현했다.
 *
 * - doInBackground() : 백그라운드 스레드에서 실행 (네트워크 작업)
 * - onPostExecute() / onError() : 메인(UI) 스레드에서 실행 (화면 갱신)
 */
public abstract class BackgroundTask<Result> {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled = false;
    private Future<?> future;

    protected abstract Result doInBackground() throws Exception;

    protected abstract void onPostExecute(Result result);

    protected void onError(Exception e) {
    }

    public final void execute() {
        future = EXECUTOR.submit(() -> {
            try {
                Result result = doInBackground();
                MAIN_HANDLER.post(() -> {
                    if (!cancelled) onPostExecute(result);
                });
            } catch (Exception e) {
                MAIN_HANDLER.post(() -> {
                    if (!cancelled) onError(e);
                });
            }
        });
    }

    /** 취소하면 결과가 와도 화면을 건드리지 않음 (액티비티 종료 시 사용) */
    public final void cancel() {
        cancelled = true;
        if (future != null) future.cancel(true);
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final boolean isRunning() {
        return future != null && !future.isDone();
    }
}
