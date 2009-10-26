/*
 * Copyright (C) 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.ocr.service;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.ocr.client.IOcrCallback;
import com.android.ocr.client.Config;

import java.util.concurrent.LinkedBlockingQueue;

public class OcrProcessor extends Thread {
  private static final String TAG = "OcrProcessor";
  
  public static final String KEY_RESULTS = "com.android.ocr.client.OcrProcessor.results";
  public static final String KEY_PROGRESS = "com.android.ocr.client.OcrProcessor.progress";
  public static final String KEY_STATUS = "com.android.ocr.client.OcrProcessor.status";

  public static final int STATE_BUSY = 0;
  public static final int STATE_RESULT = 1;
  public static final int STATE_COMPLETE = 2;
  
  private final LinkedBlockingQueue<OcrJob> mQueue;
  
  private OcrLib mOcrLib;
  private OcrJob mCurrentJob;
  private boolean mAlive;
  
  public OcrProcessor() {
    mQueue = new LinkedBlockingQueue<OcrJob>();
  }
  
  /**
   * Enqueues a new text recognition job.
   * 
   * @param jobId
   * @param config
   * @param callback
   */
  public void enqueueJob(final int jobId, Config config, IOcrCallback callback) {
    OcrJob ocrJob = new OcrJob(jobId, config, callback);
    
    IBinder binder = callback.asBinder();
    ocrJob.deathcall = new IBinder.DeathRecipient() {
      @Override
      public void binderDied() {
        Log.e(TAG, "Binder died for job " + jobId);
        cancelJob(jobId);
      }
    };

    try {
      binder.linkToDeath(ocrJob.deathcall, 0);
    } catch (RemoteException e) {
      Log.e(TAG, "Exception caught in constructor OcrJob(): " + e.toString());
    }
    
    mQueue.add(ocrJob);
  }
  
  /**
   * Cancels the OCR processing job.
   */
  public void cancelJob(int jobId) {
    Log.i(TAG, "cancelJob()");
    
    OcrJob dummy = new OcrJob(jobId, null, null);

    if (dummy.equals(mCurrentJob)) {
      mCurrentJob.canceled = true;
      mOcrLib.stop();
    } else {
      mQueue.remove(dummy);
    }
  }
  
  public int getProgress(int jobId) {
    OcrJob dummy = new OcrJob(jobId, null, null);

    if (dummy.equals(mCurrentJob)) {
      return mOcrLib.getProgress();
    } else {
      return 0;
    }
  }
  
  @Override
  public void run() {
    Log.i(TAG, "Starting job processor...");
    
    mOcrLib = OcrLib.getOcrLib();
    mAlive = true;
    
    try {
      while (mAlive) {
        mCurrentJob = mQueue.take();
        mCurrentJob.run(mOcrLib);
      }
    } catch (InterruptedException e) {
      // Interrupted also implies mAlive == false
      Log.e(TAG, "Interrupted: " + e.toString());
    }
    
    // Flush the queue, notify with null completion results
    while (!mQueue.isEmpty()) {
      mCurrentJob = mQueue.remove();
      mCurrentJob.cancelImmediate();
    }
    
    mOcrLib.close();
    mOcrLib = null;
    
    Log.i(TAG, "Stopped job processor.");
  }
  
  public void kill() {
    Log.i(TAG, "kill()");
    
    mAlive = false;
    
    interrupt();
  }
}
