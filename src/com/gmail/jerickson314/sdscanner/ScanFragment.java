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
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

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
         MediaStore.MediaColumns.DATE_MODIFIED};

    private static final String[] STAR = {"*"};

    private static final int DB_RETRIES = 3;

    ArrayList<String> mPathNames;
    TreeSet<File> mFilesToProcess;
    int mLastGoodProcessedIndex;

    private Handler mHandler = new Handler();

    int mProgressNum;
    String mProgressText;
    StringBuilder mDebugMessages;
    boolean mStartButtonEnabled;
    boolean mHasStarted = false;

    /**
     * Callback interface used by the fragment to update the Activity.
     */
    static interface ScanProgressCallbacks {
        void updateProgressNum(int progressNum);
        void updateProgressText(String progressText);
        void updateDebugMessages(String debugMessages);
        void updatePath(String path);
        void updateStartButtonEnabled(boolean startButtonEnabled);
        void signalFinished();
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

    private void resetDebugMessages() {
        mDebugMessages = new StringBuilder();
        if (mCallbacks != null) {
            mCallbacks.updateDebugMessages("");
        }
    }

    private void updateStartButtonEnabled(boolean startButtonEnabled) {
        mStartButtonEnabled = startButtonEnabled;
        if (mCallbacks != null) {
            mCallbacks.updateStartButtonEnabled(mStartButtonEnabled);
        }
    }

    private void signalFinished() {
        if (mCallbacks != null) {
            mCallbacks.signalFinished();
        }
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

    public boolean getStartButtonEnabled() {
        return mStartButtonEnabled;
    }

    public boolean getHasStarted() {
        return mHasStarted;
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
        signalFinished();
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
        mHasStarted = true;
        updateStartButtonEnabled(false);
        updateProgressText(getString(R.string.progress_filelist_label));
        mFilesToProcess = new TreeSet<File>();
        resetDebugMessages();
        if (path.exists()) {
            this.new PreprocessTask().execute(path);
        }
        else {
            updateProgressText(getString(R.string.progress_error_bad_path_label));
            updateStartButtonEnabled(true);
            signalFinished();
        }
    }

    class PreprocessTask extends AsyncTask<File, String, Void> {

        private void recursiveAddFiles(File file)
                throws IOException {
            if (file.equals(new File("/storage")) ||
                    file.equals(new File("/system"))) {
                // Don't scan a symlink that gives you all of "/storage" or
                // "/system".  May be dangerous!
                Log.w("SDScanner", "Skipping scan of " + file.toString());
                return;
            }
            if (!mFilesToProcess.add(file)) {
                // Avoid infinite recursion caused by symlinks.
                // If mFilesToProcess already contains this file, add() will 
                // return false.
                return;
            }
            if (file.isDirectory()) {
                // Debug check.
                if (new File(file, "emulated").exists()) {
                    Log.w("SDScanner", "Path " + file.getCanonicalPath() +
                          " contains 'emulated' and might be /storage");
                }
                boolean nomedia = new File(file, ".nomedia").exists();
                // Only recurse downward if not blocked by nomedia.
                if (!nomedia) {
                    File[] files = file.listFiles();
                    if (files != null) {
                        for (File nextFile : files) {
                            recursiveAddFiles(nextFile.getCanonicalFile());
                        }
                    }
                    else {
                        publishProgress("Debug",
                                getString(R.string.skipping_folder_label) +
                                " " + file.getPath());
                    }
                }
            }
        }

        protected void dbOneTry() {
            Cursor cursor = getActivity().getContentResolver().query(
                    MediaStore.Files.getContentUri("external"),
                    MEDIA_PROJECTION,
                    //STAR,
                    null,
                    null,
                    null);
            int data_column =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int modified_column =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int totalSize = cursor.getCount();
            int currentItem = 0;
            int reportFreq = 0;
            // Used to calibrate reporting frequency
            long startTime = SystemClock.currentThreadTimeMillis();
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
                        if (SystemClock.currentThreadTimeMillis() - startTime > 25) {
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
        }

        @Override
        protected Void doInBackground(File... files) {
            try {
                recursiveAddFiles(files[0].getCanonicalFile());
            }
            catch (IOException Ex) {
                // Do nothing.
            }
            // Parse database
            publishProgress("State", getString(R.string.progress_database_label));
            boolean dbSuccess = false;
            int numRetries = 0;
            while (!dbSuccess && numRetries < DB_RETRIES) {
                dbSuccess = true;
                try {
                    dbOneTry();
                }
                catch (Exception Ex) {
                    // For any of these errors, try again.
                    numRetries++;
                    dbSuccess = false;
                    if (numRetries < DB_RETRIES) {
                        publishProgress("State",
                                getString(R.string.db_error_retrying));
                        SystemClock.sleep(1000);
                    }
                }
            }
            if (numRetries > 0) {
                if (dbSuccess) {
                    publishProgress("Debug",
                                    getString(R.string.db_error_recovered));
                }
                else {
                    publishProgress("Debug",
                                    getString(R.string.db_error_failure));
                }
            }
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
