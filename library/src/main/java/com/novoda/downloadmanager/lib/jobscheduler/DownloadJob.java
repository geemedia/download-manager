package com.novoda.downloadmanager.lib.jobscheduler;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.evernote.android.job.Job;
import com.evernote.android.job.JobRequest;
import com.evernote.android.job.util.support.PersistableBundleCompat;
import com.novoda.downloadmanager.lib.DownloadServiceJob;
import com.novoda.downloadmanager.lib.DownloadStatus;
import com.novoda.downloadmanager.lib.logger.LLog;

import java.util.concurrent.TimeUnit;

public class DownloadJob extends Job {

    public static final String TAG = "download_job_tag";

    private static final String ENFORCE_EXECUTION = "enforceExecution";
    private static final boolean STANDARD_EXECUTION = false;
    private static final boolean ENFORCED_EXECUTION = true;
    private static final long BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long EXECUTION_START_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long EXECUTION_END_MILLIS = TimeUnit.SECONDS.toMillis(2);

    private final static Object lock = new Object();

    @NonNull
    @Override
    protected Result onRunJob(Params params) {
        LLog.v("job starts right now");

        final DownloadServiceJob[] downloadServiceJob = new DownloadServiceJob[1];

        ensureDownloadServiceJobInstanceExists(downloadServiceJob);
        waitForDownloadServiceJobInstanceToBeReady();

        PersistableBundleCompat extras = params.getExtras();
        boolean enforceExecution = extras.getBoolean(ENFORCE_EXECUTION, STANDARD_EXECUTION);

        int status = downloadServiceJob[0].onStartCommand(enforceExecution);

        if (jobHasSucceeded(status)) {
            LLog.v("job is completed");
            return Result.SUCCESS;
        } else if (jobNeedsRescheduling(status)) {
            LLog.v("job is going to be rescheduled");
            return Result.RESCHEDULE;
        } else {
            LLog.v("job failure");
            return Result.FAILURE;
        }
    }

    private void ensureDownloadServiceJobInstanceExists(final DownloadServiceJob[] downloadServiceJob) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                downloadServiceJob[0] = DownloadServiceJob.getInstance();
                downloadServiceJobInstanceIsReady();
            }
        });
    }

    private boolean jobHasSucceeded(int status) {
        return DownloadStatus.isCompleted(status) || DownloadStatus.isPausedByApp(status);
    }

    private boolean jobNeedsRescheduling(int status) {
        return DownloadStatus.isPendingForNetwork(status) || DownloadStatus.isPausedByAppRestrictions(status);
    }

    private void waitForDownloadServiceJobInstanceToBeReady() {
        synchronized (lock) {
            try {
                LLog.v("Waiting for download service job instance to be ready");
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void downloadServiceJobInstanceIsReady() {
        synchronized (lock) {
            LLog.v("Download service job instance is ready now");
            lock.notifyAll();
        }
    }

    public static void scheduleJob() {
        LLog.v("scheduling a job to start immediately in 1s");
        scheduleJob(EXECUTION_START_MILLIS, STANDARD_EXECUTION);
    }

    public static void scheduleEnforcedJob() {
        LLog.v("scheduling a job to start immediately in 1s");
        scheduleJob(EXECUTION_START_MILLIS, ENFORCED_EXECUTION);
    }

    private static void scheduleJob(final long startMillis, final boolean enforceJob) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                PersistableBundleCompat extras = new PersistableBundleCompat();
                extras.putBoolean(ENFORCE_EXECUTION, enforceJob);

                new JobRequest.Builder(TAG)
                        .setExecutionWindow(startMillis, EXECUTION_END_MILLIS)
                        .setBackoffCriteria(BACKOFF_MILLIS, JobRequest.BackoffPolicy.LINEAR)
                        .setRequiresDeviceIdle(false)
                        .setRequirementsEnforced(true)
                        .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
                        .setPersisted(true)
                        .setExtras(extras)
                        .build()
                        .schedule();
            }
        });
    }
}
