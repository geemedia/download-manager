package com.novoda.downloadmanager.demo.simple;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.novoda.downloadmanager.Downloader;
import com.novoda.downloadmanager.OnDownloadsUpdateListener;
import com.novoda.downloadmanager.demo.R;
import com.novoda.downloadmanager.domain.Download;
import com.novoda.downloadmanager.domain.DownloadId;
import com.novoda.downloadmanager.domain.DownloadRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String BIG_FILE_URL = "http://ipv4.download.thinkbroadband.com/100MB.zip";
    private static final String SMALL_FILE_URL = "http://ipv4.download.thinkbroadband.com/5MB.zip";
    private static final String PENGUINS_IMAGE_URL = "http://i.imgur.com/Y7pMO5Kb.jpg";

    private final DeleteAllDownloadsAction deleteAllDownloadsAction = new DeleteAllDownloadsAction();

    private View emptyView;
    private DownloadAdapter adapter;

    private DownloaderHelper downloaderHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Downloader downloader = new Downloader.Builder().build(this);

        downloaderHelper = new DownloaderHelper(downloader);

        emptyView = findViewById(R.id.main_no_downloads_view);
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.main_downloads_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter(onDownloadClickedListener);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.main_download_button).setOnClickListener(downloadClickListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.demo, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_delete_all) {
            deleteAllDownloadsAction.run();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final View.OnClickListener downloadClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            DownloadRequest downloadRequest = createDownloadRequest();
            downloaderHelper.submit(downloadRequest);
        }
    };

    private DownloadRequest createDownloadRequest() {
        DownloadRequest.File bigFile = createFileRequest(BIG_FILE_URL, "super_big.file");
        DownloadRequest.File bigFile2 = createFileRequest(PENGUINS_IMAGE_URL, "penguins.important");
        DownloadRequest.File bigFile3 = createFileRequest(SMALL_FILE_URL, "very.small");
        return new DownloadRequest.Builder()
                .with(downloaderHelper.createDownloadId())
                .withFile(bigFile)
                .withFile(bigFile2)
                .withFile(bigFile3)
                .build();
    }

    private DownloadRequest.File createFileRequest(String uri, String filename) {
        File file = new File(getCacheDir(), filename);
        return new DownloadRequest.File(uri, file.getAbsolutePath(), filename);
    }

    private final DownloadAdapter.OnDownloadClickedListener onDownloadClickedListener = new DownloadAdapter.OnDownloadClickedListener() {

        @Override
        public void onDeleteDownload(Download download) {
            downloaderHelper.delete(download.getId());
        }

        @Override
        public void onPauseDownload(Download download) {
            toastMessage("Pausing download!");
            downloaderHelper.pause(download.getId());
        }

        @Override
        public void onResumeDownload(Download download) {
            toastMessage("Resuming download!");
            downloaderHelper.resume(download.getId());
        }

        private void toastMessage(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        downloaderHelper.startWatching(onDownloadsUpdate);
        downloaderHelper.requestDownloadsUpdate();
    }

    @Override
    protected void onPause() {
        downloaderHelper.stopWatching(onDownloadsUpdate);
        super.onPause();
    }

    private final OnDownloadsUpdateListener onDownloadsUpdate = new OnDownloadsUpdateListener() {
        @Override
        public void onDownloadsUpdate(List<Download> downloads) {
            deleteAllDownloadsAction.update(downloads);
            adapter.update(downloads);
            emptyView.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
        }
    };

    private class DeleteAllDownloadsAction implements Runnable {

        private final List<DownloadId> downloadIds = new ArrayList<>();

        public void update(List<Download> downloads) {
            this.downloadIds.clear();
            for (Download download : downloads) {
                downloadIds.add(download.getId());
            }
        }

        @Override
        public void run() {
            for (DownloadId downloadId : downloadIds) {
                downloaderHelper.delete(downloadId);
            }
        }
    }

}
