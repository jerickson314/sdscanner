/* SD Scanner - A manual implementation of the SD rescan process, compatible
 * with Android 4.4
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
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

public class MainActivity extends Activity
{
    ArrayList<String> mPathNames;
    int mLastGoodProcessedIndex = -1;

    private Handler mHandler = new Handler();

    public void setDefault() {
        EditText pathText = (EditText) findViewById(R.id.path_widget);
        pathText.setText(
                Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        setDefault();
    }

    public void defaultButtonPressed(View view) {
        setDefault();
    }

    public void startButtonPressed(View view) {
        Button startButton = (Button)findViewById(R.id.start_button);
        startButton.setEnabled(false);
        TextView progressLabel = (TextView)findViewById(R.id.progress_label);
        progressLabel.setText(R.string.progress_phase1_label);
        EditText pathText = (EditText) findViewById(R.id.path_widget);
        String path = pathText.getText().toString();
        mPathNames = recursiveListFiles(new File(path));
        MediaScannerConnection.scanFile(
            getApplicationContext(),
            mPathNames.toArray(new String[mPathNames.size()]),
            null,
            this.new ProgressListener());
    }
        
    private ArrayList<String> recursiveListFiles(File directory) {
        ArrayList<String> toReturn = new ArrayList<String>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                toReturn.addAll(recursiveListFiles(file));
            }
            else {
                toReturn.add(file.getAbsolutePath());
            }
        }
        return toReturn;
    }

    class ProgressListener
            implements MediaScannerConnection.OnScanCompletedListener {
        @Override
        public void onScanCompleted(String path, Uri uri) {
            mHandler.post(new Updater(path));
        }
    }

    class Updater implements Runnable {
        String mPath;

        public Updater(String path) {
            mPath = path;
        }

        public void run() {
            if (mPathNames.get(mLastGoodProcessedIndex
                              + 1).equals(mPath)) {
                mLastGoodProcessedIndex++;
            }
            else {
                int newIndex = mPathNames.indexOf(mPath);
                if (newIndex > -1) {
                    mLastGoodProcessedIndex = newIndex;
                }
            }
            int progress = (100 * (mLastGoodProcessedIndex + 1))
                           / mPathNames.size();
            ProgressBar progressBar =
                    (ProgressBar)findViewById(R.id.progress_bar);
            TextView progressLabel =
                    (TextView)findViewById(R.id.progress_label);
            if (progress == 100) {
                Button startButton =
                        (Button)findViewById(R.id.start_button);
                startButton.setEnabled(true);
                progressBar.setProgress(0);
                progressLabel.setText(getString(
                        R.string.progress_unstarted_label));
            }
            else {
                progressBar.setProgress(progress);

                progressLabel.setText(getString(R.string.processing)
                                      + " " + mPath);
            }
        }
    }
}
