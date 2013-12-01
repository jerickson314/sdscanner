/* SD Scanner - A manual implementation of the SD rescan process, compatible
 * with Android 4.4.
 *
 * This file contains the fragment that actually performs all scan activity
 * and retains state across configuration changes.
 * 
 * Copyright (C) 2013 Jeremy Erickson
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.gmail.jerickson314.sdscanner;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentUris;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.TreeSet;

public class ScanFragment extends Fragment {

    private static final String[] MEDIA_PROJECTION =
        {MediaStore.MediaColumns.DATA,
         MediaStore.MediaColumns.DATE_MODIFIED,
         MediaStore.MediaColumns._ID};

    private static final String[] STAR = {"*"};

    ArrayList<String> mPathNames;
    TreeSet<File> mFilesToProcess;
    int mLastGoodProcessedIndex;

    private Handler mHandler = new Handler();

    int mProgressNum;
    String mProgressText;
    StringBuilder mDebugMessages;
    String mPath;
    boolean mStartButtonEnabled;

    /**
     * Callback interface used by the fragment to update the Activity.
     */
    static interface ScanProgressCallbacks {
        void updateProgressNum(int progressNum);
        void updateProgressText(String progressText);
        void updateDebugMessages(String debugMessages);
        void updatePath(String path);
        void updateStartButtonEnabled(boolean startButtonEnabled);
    }

    private ScanProgressCallbacks mCallbacks;

    private void updateProgressNum(int progressNum) {
        mProgressNum = progressNum;
        if (mCallbacks != null) {
            mCallbacks.updateProgressNum(mProgressNum);
        }
    }

    private void updateProgressText(String progressText) {
        mProgressText = progressText;
        if (mCallbacks != null) {
            mCallbacks.updateProgressText(mProgressText);
        }
    }

    private void addDebugMessage(String debugMessage) {
        mDebugMessages.append(debugMessage + "\n");
        if (mCallbacks != null) {
            mCallbacks.updateDebugMessages(mDebugMessages.toString());
        }
    }

    private void updateStartButtonEnabled(boolean startButtonEnabled) {
        mStartButtonEnabled = startButtonEnabled;
        if (mCallbacks != null) {
            mCallbacks.updateStartButtonEnabled(mStartButtonEnabled);
        }
    }

    public void setPath(String path) {
        mPath = path;
    }

    public int getProgressNum() {
        return mProgressNum;
    }

    public String getProgressText() {
        return mProgressText;
    }

    public String getDebugMessages() {
        return mDebugMessages.toString();
    }

    public String getPath() {
        return mPath;
    }

    public boolean getStartButtonEnabled() {
        return mStartButtonEnabled;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mCallbacks = (ScanProgressCallbacks) activity;
    }

    public ScanFragment() {
        super();

        // Set correct initial values.
        mProgressNum = 0;
        mDebugMessages = new StringBuilder();
        try {
            mPath = Environment.getExternalStorageDirectory().getCanonicalPath();
        }
        catch (IOException Ex) {
            // Do nothing.
        }
        mStartButtonEnabled = true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retain this fragment across configuration changes.
        setRetainInstance(true);
        updateProgressText(getString(R.string.progress_unstarted_label));
    }

    public void listPathNamesOnDebug() {
        StringBuffer listString = new StringBuffer();
        listString.append("\n\nScanning paths:\n");
        Iterator<String> iterator = mPathNames.iterator();
        while (iterator.hasNext()) {
            listString.append(iterator.next() + "\n");
        }
        addDebugMessage(listString.toString());
    }

    public void scannerEnded() {
        updateProgressNum(0);
        updateProgressText(getString(R.string.progress_completed_label));
        updateStartButtonEnabled(true);
    }

    public void startMediaScanner(){
        //listPathNamesOnDebug();
        if (mPathNames.size() == 0) {
            scannerEnded();
        }
        else {
            MediaScannerConnection.scanFile(
                getActivity().getApplicationContext(),
                mPathNames.toArray(new String[mPathNames.size()]),
                null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    public void onScanCompleted(String path, Uri uri) {
                        mHandler.post(new Updater(path));
                    }
                });
        }
    }
    
    public void startScan(File path) {
        updateStartButtonEnabled(false);
        updateProgressText(getString(R.string.progress_filelist_label));
        mFilesToProcess = new TreeSet<File>();
        mDebugMessages = new StringBuilder();
        if (path.exists()) {
            this.new PreprocessTask().execute(path);
        }
        else {
            updateProgressText(getString(R.string.progress_error_bad_path_label));
            updateStartButtonEnabled(true);
        }
    }

    class PreprocessTask extends AsyncTask<File, String, Void> {

        class DeletionEntry {
            String mPath;
            int mId;
            public DeletionEntry(String path, int id) {
                mPath = path;
                mId = id;
            }

            public String getPath() {
                return mPath;
            }

            public int getId() {
                return mId;
            }
        }

        private void recursiveAddFiles(File file)
                throws IOException {
            if (file.isDirectory()) {
                boolean nomedia = new File(file, ".nomedia").exists();
                // Only recurse downward if not blocked by nomedia.
                if (!nomedia) {
                    for (File nextFile : file.listFiles()) {
                        recursiveAddFiles(nextFile);
                    }
                }
            }
            mFilesToProcess.add(file.getCanonicalFile());
        }

        @Override
        protected Void doInBackground(File... files) {
            try {
                recursiveAddFiles(files[0]);
            }
            catch (IOException Ex) {
                // Do nothing.
            }
            // Parse database
            publishProgress("State", getString(R.string.progress_database_label));
            Cursor cursor = getActivity().getContentResolver().query(
                    MediaStore.Files.getContentUri("external"),
                    //MEDIA_PROJECTION,
                    STAR,
                    null,
                    null,
                    null);
            int data_column =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int modified_column =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int id_column =
                    cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int totalSize = cursor.getCount();
            int currentItem = 0;
            int reportFreq = 0;
            // Manually delete things where .nomedia is the reason.
            ArrayList<DeletionEntry> toDelete = new ArrayList<DeletionEntry>();
            // Used to calibrate reporting frequency
            long startTime = System.currentTimeMillis();
            while (cursor.moveToNext()) {
                currentItem++;
                try {
                    File file = new File(cursor.getString(data_column)).getCanonicalFile();
                    if (!file.exists() ||
                             file.lastModified() / 1000L >
                             cursor.getLong(modified_column)) {
                        // Media scanner handles these cases.
                        // Is a set, so OK if already present.
                        mFilesToProcess.add(file);
                    }
                    else {
                        // Don't want to waste time scanning an up-to-date
                        // file.
                        mFilesToProcess.remove(file);
                    }
                    if (reportFreq == 0) {
                        // Calibration phase
                        if (System.currentTimeMillis() - startTime > 25) {
                            reportFreq = currentItem + 1;
                        }
                    }
                    else if (currentItem % reportFreq == 0) {
                        publishProgress("Database",
                                        file.getPath(),
                                        Integer.toString((100 * currentItem)
                                                         / totalSize));
                    }
                }
                catch (IOException ex) {
                    // Just ignore it for now.
                }
            }
            // Don't need the cursor any more.
            cursor.close();

            // Prepare final path list for processing.
            mPathNames = new ArrayList<String>(mFilesToProcess.size());
            Iterator<File> iterator = mFilesToProcess.iterator();
            while (iterator.hasNext()) {
                mPathNames.add(iterator.next().getPath());
            }
            mLastGoodProcessedIndex = -1;

            return null;
        }

        @Override
        protected void onProgressUpdate(String... progress) {
            String startText = "";
            if (progress[0].equals("Database")) {
                startText = getString(R.string.database_proc);
                updateProgressText(startText + " " + progress[1]);
                updateProgressNum(Integer.parseInt(progress[2]));
            }
            else if (progress[0].equals("Delete")) {
                startText = getString(R.string.delete_proc);
                updateProgressText(startText + " " + progress[1]);
                updateProgressNum(Integer.parseInt(progress[2]));
            }
            else if (progress[0].equals("State")) {
                updateProgressText(progress[1]);
                updateProgressNum(0);
            }
            else if (progress[0].equals("Debug")) {
                addDebugMessage(progress[1]);
            }
        }

        @Override
        protected void onPostExecute(Void result) {
            startMediaScanner();
        }
    }

    class Updater implements Runnable {
        String mPathScanned;

        public Updater(String path) {
            mPathScanned = path;
        }

        public void run() {
            if (mLastGoodProcessedIndex + 1 < mPathNames.size() &&
                mPathNames.get(mLastGoodProcessedIndex
                              + 1).equals(mPathScanned)) {
                mLastGoodProcessedIndex++;
            }
            else {
                int newIndex = mPathNames.indexOf(mPathScanned);
                if (newIndex > -1) {
                    mLastGoodProcessedIndex = newIndex;
                }
            }
            int progress = (100 * (mLastGoodProcessedIndex + 1))
                           / mPathNames.size();
            if (progress == 100) {
                scannerEnded();
            }
            else {
                updateProgressNum(progress);
                updateProgressText(getString(R.string.final_proc) + " "
                                   + mPathScanned);
            }
        }
    }
}
