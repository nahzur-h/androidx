/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.remotecallback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BasicWidgetProviderTest {

    private static Uri sUri;
    private static String sStr;
    private static int sInt;
    private static Integer sNullableInt;
    private static CountDownLatch sLatch;

    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Test
    public void testRemoteCallback() {
        Uri aUri = new Uri.Builder().authority("mine").build();
        String something = "something";
        int i = 42;

        RemoteCallback callback = new WidgetProvider().createRemoteCallback(
                mContext).myCallbackMethod(aUri, something, i, null);

        assertNotNull(callback);
        assertEquals("myCallbackMethod", callback.getMethodName());
        assertEquals(WidgetProvider.class.getName(), callback.getReceiverClass());
        assertEquals(RemoteCallback.TYPE_RECEIVER, callback.getType());
    }

    @Test
    public void testCreateStatic() throws PendingIntent.CanceledException, InterruptedException {
        Uri aUri = new Uri.Builder().authority("mine").build();
        String something = "something";
        int i = 42;
        sLatch = new CountDownLatch(1);

        resetState();

        RemoteCallback.create(WidgetProvider.class, mContext).myCallbackMethod(
                aUri, something, i, null).toPendingIntent().send();

        sLatch.await(2, TimeUnit.SECONDS);

        assertState(0, aUri, something, i);
    }

    @Test
    public void testCreateCallback() throws PendingIntent.CanceledException, InterruptedException {
        Uri aUri = new Uri.Builder().authority("mine").build();
        String something = "something";
        int i = 42;
        sLatch = new CountDownLatch(1);

        resetState();

        new WidgetProvider().createRemoteCallback(mContext).myCallbackMethod(
                aUri, something, i, null).toPendingIntent().send();

        sLatch.await(2, TimeUnit.SECONDS);

        assertState(0, aUri, something, i);
    }

    @Test
    public void testOverrideNull() throws PendingIntent.CanceledException, InterruptedException {
        Uri aUri = new Uri.Builder().authority("mine").build();
        String something = "something";
        int i = 42;

        resetState();

        Intent intent = new Intent()
                .putExtra("p3", 3);
        new WidgetProvider().createRemoteCallback(mContext).myCallbackMethod(
                aUri, something, i, null).toPendingIntent().send(mContext, 0, intent);

        sLatch.await(2, TimeUnit.SECONDS);

        assertState(0, aUri, something, i);
    }

    private void resetState() {
        sLatch = new CountDownLatch(1);

        sUri = null;
        sStr = null;
        sInt = -1;
        sNullableInt = 15;
    }

    private void assertState(int count, Uri aUri, String something, int i) {
        assertEquals(count, sLatch.getCount());
        assertEquals(aUri, sUri);
        assertEquals(something, sStr);
        assertEquals(i, sInt);
        assertNull(sNullableInt);
    }

    public static class WidgetProvider extends AppWidgetProviderWithCallbacks<WidgetProvider> {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("BasicWidgetProviderTest", "onReceive " + intent);
            super.onReceive(context, intent);
        }

        @RemoteCallable
        public RemoteCallback myCallbackMethod(Uri myUri, String myStr, int myInt,
                Integer myNullableInt) {
            Log.d("BasicWidgetProviderTest",
                    "myCallbackMethod " + myUri + " " + myStr + " " + myInt + " " + myNullableInt,
                    new Throwable());
            sUri = myUri;
            sStr = myStr;
            sInt = myInt;
            sNullableInt = myNullableInt;
            if (sLatch != null) sLatch.countDown();
            return RemoteCallback.LOCAL;
        }
    }
}
