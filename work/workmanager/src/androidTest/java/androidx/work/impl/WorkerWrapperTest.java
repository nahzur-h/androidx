/*
 * Copyright 2017 The Android Open Source Project
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

package androidx.work.impl;

import static androidx.work.State.BLOCKED;
import static androidx.work.State.CANCELLED;
import static androidx.work.State.ENQUEUED;
import static androidx.work.State.FAILED;
import static androidx.work.State.RUNNING;
import static androidx.work.State.SUCCEEDED;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import androidx.work.ArrayCreatingInputMerger;
import androidx.work.Data;
import androidx.work.DatabaseTest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.impl.model.Dependency;
import androidx.work.impl.model.DependencyDao;
import androidx.work.impl.model.WorkSpec;
import androidx.work.impl.model.WorkSpecDao;
import androidx.work.impl.utils.taskexecutor.InstantTaskExecutorRule;
import androidx.work.worker.ChainedArgumentWorker;
import androidx.work.worker.EchoingWorker;
import androidx.work.worker.FailureWorker;
import androidx.work.worker.RetryWorker;
import androidx.work.worker.SleepTestWorker;
import androidx.work.worker.TestWorker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class WorkerWrapperTest extends DatabaseTest {
    private WorkSpecDao mWorkSpecDao;
    private DependencyDao mDependencyDao;
    private Context mContext;
    private ExecutionListener mMockListener;
    private Scheduler mMockScheduler;

    @Rule
    public InstantTaskExecutorRule mRule = new InstantTaskExecutorRule();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mWorkSpecDao = spy(mDatabase.workSpecDao());
        mDependencyDao = mDatabase.dependencyDao();
        mMockListener = mock(ExecutionListener.class);
        mMockScheduler = mock(Scheduler.class);
    }

    @Test
    @SmallTest
    public void testSuccess() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), true, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(SUCCEEDED));
    }

    @Test
    @SmallTest
    public void testRunAttemptCountIncremented_successfulExecution() {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .withListener(mMockListener)
                .build()
                .run();
        WorkSpec latestWorkSpec = mWorkSpecDao.getWorkSpec(work.getId());
        assertThat(latestWorkSpec.runAttemptCount, is(1));
    }

    @Test
    @SmallTest
    public void testRunAttemptCountIncremented_failedExecution() {
        WorkRequest work = new WorkRequest.Builder(FailureWorker.class).build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .withListener(mMockListener)
                .build()
                .run();
        WorkSpec latestWorkSpec = mWorkSpecDao.getWorkSpec(work.getId());
        assertThat(latestWorkSpec.runAttemptCount, is(1));
    }

    @Test
    @SmallTest
    public void testPermanentErrorWithInvalidWorkSpecId() throws InterruptedException {
        final String invalidWorkSpecId = "INVALID_ID";
        new WorkerWrapper.Builder(mContext, mDatabase, invalidWorkSpecId)
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(invalidWorkSpecId, false, false);
    }

    @Test
    @SmallTest
    public void testNotEnqueued() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(RUNNING)
                .build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), false, true);
    }

    @Test
    @SmallTest
    public void testCancelled() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(CANCELLED)
                .build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), false, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testPermanentErrorWithInvalidWorkerClass() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        getWorkSpec(work).workerClassName = "INVALID_CLASS_NAME";
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), false, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(FAILED));
    }

    @Test
    @SmallTest
    public void testPermanentErrorWithInvalidInputMergerClass() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        getWorkSpec(work).inputMergerClassName = "INVALID_CLASS_NAME";
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), false, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(FAILED));
    }

    @Test
    @SmallTest
    public void testFailed() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(FailureWorker.class).build();
        insertWork(work);
        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build()
                .run();
        verify(mMockListener).onExecuted(work.getId(), false, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(FAILED));
    }

    @Test
    @LargeTest
    public void testRunning() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(SleepTestWorker.class).build();
        insertWork(work);
        WorkerWrapper wrapper = new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withListener(mMockListener)
                .build();
        Executors.newSingleThreadExecutor().submit(wrapper);
        Thread.sleep(2000L); // Async wait duration.
        assertThat(mWorkSpecDao.getState(work.getId()), is(RUNNING));
        Thread.sleep(SleepTestWorker.SLEEP_DURATION);
        verify(mMockListener).onExecuted(work.getId(), true, false);
    }

    @Test
    @SmallTest
    public void testDependencies() {
        WorkRequest prerequisiteWork = new WorkRequest.Builder(TestWorker.class).build();
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(BLOCKED).build();
        Dependency dependency = new Dependency(work.getId(), prerequisiteWork.getId());

        mDatabase.beginTransaction();
        try {
            insertWork(prerequisiteWork);
            insertWork(work);
            mDependencyDao.insertDependency(dependency);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        assertThat(mWorkSpecDao.getState(prerequisiteWork.getId()), is(ENQUEUED));
        assertThat(mWorkSpecDao.getState(work.getId()), is(BLOCKED));
        assertThat(mDependencyDao.hasCompletedAllPrerequisites(work.getId()), is(false));

        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork.getId())
                .withListener(mMockListener)
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build()
                .run();

        assertThat(mWorkSpecDao.getState(prerequisiteWork.getId()), is(SUCCEEDED));
        assertThat(mWorkSpecDao.getState(work.getId()), is(ENQUEUED));
        assertThat(mDependencyDao.hasCompletedAllPrerequisites(work.getId()), is(true));

        ArgumentCaptor<WorkSpec> captor = ArgumentCaptor.forClass(WorkSpec.class);
        verify(mMockScheduler).schedule(captor.capture());
        assertThat(captor.getValue().id, is(work.getId()));
    }

    @Test
    @SmallTest
    public void testDependencies_passesOutputs() {
        WorkRequest prerequisiteWork = new WorkRequest.Builder(ChainedArgumentWorker.class).build();
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(BLOCKED)
                .build();
        Dependency dependency = new Dependency(work.getId(), prerequisiteWork.getId());

        mDatabase.beginTransaction();
        try {
            insertWork(prerequisiteWork);
            insertWork(work);
            mDependencyDao.insertDependency(dependency);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build().run();

        List<Data> arguments = mWorkSpecDao.getInputsFromPrerequisites(work.getId());
        assertThat(arguments.size(), is(1));
        assertThat(arguments, contains(ChainedArgumentWorker.getChainedArguments()));
    }

    @Test
    @SmallTest
    public void testDependencies_passesMergedOutputs() {
        String key = "key";
        String value1 = "value1";
        String value2 = "value2";

        WorkRequest prerequisiteWork1 = new WorkRequest.Builder(EchoingWorker.class)
                .withInputData(new Data.Builder().putString(key, value1).build())
                .build();
        WorkRequest prerequisiteWork2 = new WorkRequest.Builder(EchoingWorker.class)
                .withInputData(new Data.Builder().putString(key, value2).build())
                .build();
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInputMerger(ArrayCreatingInputMerger.class)
                .build();
        Dependency dependency1 = new Dependency(work.getId(), prerequisiteWork1.getId());
        Dependency dependency2 = new Dependency(work.getId(), prerequisiteWork2.getId());

        mDatabase.beginTransaction();
        try {
            insertWork(prerequisiteWork1);
            insertWork(prerequisiteWork2);
            insertWork(work);
            mDependencyDao.insertDependency(dependency1);
            mDependencyDao.insertDependency(dependency2);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Run the prerequisites.
        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork1.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build().run();

        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork2.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build().run();

        // Create and run the dependent work.
        WorkerWrapper workerWrapper = new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build();
        workerWrapper.run();

        Data input = workerWrapper.mWorker.getInputData();
        assertThat(input.size(), is(1));
        assertThat(Arrays.asList(input.getStringArray(key)),
                containsInAnyOrder(value1, value2));
    }

    @Test
    @SmallTest
    public void testDependencies_setsPeriodStartTimesForUnblockedWork() {
        WorkRequest prerequisiteWork = new WorkRequest.Builder(TestWorker.class).build();
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(BLOCKED)
                .build();
        Dependency dependency = new Dependency(work.getId(), prerequisiteWork.getId());

        mDatabase.beginTransaction();
        try {
            insertWork(prerequisiteWork);
            insertWork(work);
            mDependencyDao.insertDependency(dependency);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        long beforeUnblockedTime = System.currentTimeMillis();

        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork.getId())
                .withListener(mMockListener)
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .build()
                .run();

        WorkSpec workSpec = mWorkSpecDao.getWorkSpec(work.getId());
        assertThat(workSpec.periodStartTime, is(greaterThan(beforeUnblockedTime)));
    }

    @Test
    @SmallTest
    public void testDependencies_failsUncancelledDependentsOnFailure() {
        WorkRequest prerequisiteWork = new WorkRequest.Builder(FailureWorker.class).build();
        WorkRequest work = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(BLOCKED)
                .build();
        WorkRequest cancelledWork = new WorkRequest.Builder(TestWorker.class)
                .withInitialState(CANCELLED)
                .build();
        Dependency dependency1 = new Dependency(work.getId(), prerequisiteWork.getId());
        Dependency dependency2 = new Dependency(cancelledWork.getId(), prerequisiteWork.getId());

        mDatabase.beginTransaction();
        try {
            insertWork(prerequisiteWork);
            insertWork(work);
            insertWork(cancelledWork);
            mDependencyDao.insertDependency(dependency1);
            mDependencyDao.insertDependency(dependency2);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        new WorkerWrapper.Builder(mContext, mDatabase, prerequisiteWork.getId()).build().run();

        assertThat(mWorkSpecDao.getState(prerequisiteWork.getId()), is(FAILED));
        assertThat(mWorkSpecDao.getState(work.getId()), is(FAILED));
        assertThat(mWorkSpecDao.getState(cancelledWork.getId()), is(CANCELLED));
    }

    @Test
    @SmallTest
    public void testRun_periodicWork_success_updatesPeriodStartTime() {
        long intervalDuration = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS;
        long periodStartTime = System.currentTimeMillis();
        long expectedNextPeriodStartTime = periodStartTime + intervalDuration;

        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                TestWorker.class, intervalDuration, TimeUnit.MILLISECONDS).build();

        getWorkSpec(periodicWork).periodStartTime = periodStartTime;

        insertWork(periodicWork);

        new WorkerWrapper.Builder(mContext, mDatabase, periodicWork.getId())
                .withListener(mMockListener)
                .build()
                .run();

        WorkSpec updatedWorkSpec = mWorkSpecDao.getWorkSpec(periodicWork.getId());
        assertThat(updatedWorkSpec.periodStartTime, is(expectedNextPeriodStartTime));
    }

    @Test
    @SmallTest
    public void testRun_periodicWork_failure_updatesPeriodStartTime() {
        long intervalDuration = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS;
        long periodStartTime = System.currentTimeMillis();
        long expectedNextPeriodStartTime = periodStartTime + intervalDuration;

        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                FailureWorker.class, intervalDuration, TimeUnit.MILLISECONDS).build();

        getWorkSpec(periodicWork).periodStartTime = periodStartTime;

        insertWork(periodicWork);

        new WorkerWrapper.Builder(mContext, mDatabase, periodicWork.getId())
                .withListener(mMockListener)
                .build()
                .run();

        WorkSpec updatedWorkSpec = mWorkSpecDao.getWorkSpec(periodicWork.getId());
        assertThat(updatedWorkSpec.periodStartTime, is(expectedNextPeriodStartTime));
    }

    @Test
    @SmallTest
    public void testPeriodicWork_success() throws InterruptedException {
        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                TestWorker.class,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .build();

        final String periodicWorkId = periodicWork.getId();
        insertWork(periodicWork);
        new WorkerWrapper.Builder(mContext, mDatabase, periodicWorkId)
                .withListener(mMockListener)
                .build()
                .run();

        WorkSpec periodicWorkSpecAfterFirstRun = mWorkSpecDao.getWorkSpec(periodicWorkId);
        verify(mMockListener).onExecuted(periodicWorkId, true, false);
        assertThat(periodicWorkSpecAfterFirstRun.runAttemptCount, is(0));
        assertThat(periodicWorkSpecAfterFirstRun.state, is(ENQUEUED));
    }

    @Test
    @SmallTest
    public void testPeriodicWork_fail() throws InterruptedException {
        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                FailureWorker.class,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .build();

        final String periodicWorkId = periodicWork.getId();
        insertWork(periodicWork);
        new WorkerWrapper.Builder(mContext, mDatabase, periodicWorkId)
                .withListener(mMockListener)
                .build()
                .run();

        WorkSpec periodicWorkSpecAfterFirstRun = mWorkSpecDao.getWorkSpec(periodicWorkId);
        verify(mMockListener).onExecuted(periodicWorkId, false, false);
        assertThat(periodicWorkSpecAfterFirstRun.runAttemptCount, is(0));
        assertThat(periodicWorkSpecAfterFirstRun.state, is(ENQUEUED));
    }

    @Test
    @SmallTest
    public void testPeriodicWork_retry() throws InterruptedException {
        PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                RetryWorker.class,
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .build();

        final String periodicWorkId = periodicWork.getId();
        insertWork(periodicWork);
        new WorkerWrapper.Builder(mContext, mDatabase, periodicWorkId)
                .withListener(mMockListener)
                .build()
                .run();

        WorkSpec periodicWorkSpecAfterFirstRun = mWorkSpecDao.getWorkSpec(periodicWorkId);
        verify(mMockListener).onExecuted(periodicWorkId, false, true);
        assertThat(periodicWorkSpecAfterFirstRun.runAttemptCount, is(1));
        assertThat(periodicWorkSpecAfterFirstRun.state, is(ENQUEUED));
    }

    @Test
    @SmallTest
    public void testScheduler() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(work);
        Scheduler mockScheduler = mock(Scheduler.class);

        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mockScheduler))
                .build()
                .run();

        verify(mockScheduler).schedule();
    }

    @Test
    @SmallTest
    public void testFromWorkSpec_hasAppContext() throws InterruptedException {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        Worker worker = WorkerWrapper.workerFromWorkSpec(
                mContext,
                getWorkSpec(work),
                Data.EMPTY,
                null);

        assertThat(worker, is(notNullValue()));
        assertThat(worker.getAppContext(), is(equalTo(mContext.getApplicationContext())));
    }

    @Test
    @SmallTest
    public void testFromWorkSpec_hasCorrectArguments() throws InterruptedException {
        String key = "KEY";
        String expectedValue = "VALUE";
        Data input = new Data.Builder().putString(key, expectedValue).build();

        WorkRequest work = new WorkRequest.Builder(TestWorker.class).withInputData(input).build();
        Worker worker = WorkerWrapper.workerFromWorkSpec(
                mContext,
                getWorkSpec(work),
                input,
                null);

        assertThat(worker, is(notNullValue()));
        assertThat(worker.getInputData().getString(key, null), is(expectedValue));

        work = new WorkRequest.Builder(TestWorker.class).build();
        worker = WorkerWrapper.workerFromWorkSpec(
                mContext,
                getWorkSpec(work),
                Data.EMPTY,
                null);

        assertThat(worker, is(notNullValue()));
        assertThat(worker.getInputData().size(), is(0));
    }

    @Test
    @SmallTest
    public void testSuccess_withPendingScheduledWork() {
        WorkRequest work = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(work);

        WorkRequest unscheduled = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(unscheduled);

        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .withListener(mMockListener)
                .build()
                .run();

        verify(mMockScheduler, times(1)).schedule(unscheduled.getWorkSpec());
        verify(mMockListener).onExecuted(work.getId(), true, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(SUCCEEDED));
    }

    @Test
    @SmallTest
    public void testFailure_withPendingScheduledWork() {
        WorkRequest work = new WorkRequest.Builder(FailureWorker.class).build();
        insertWork(work);

        WorkRequest unscheduled = new WorkRequest.Builder(TestWorker.class).build();
        insertWork(unscheduled);

        new WorkerWrapper.Builder(mContext, mDatabase, work.getId())
                .withSchedulers(Collections.singletonList(mMockScheduler))
                .withListener(mMockListener)
                .build()
                .run();

        verify(mMockScheduler, times(1)).schedule(unscheduled.getWorkSpec());
        verify(mMockListener).onExecuted(work.getId(), false, false);
        assertThat(mWorkSpecDao.getState(work.getId()), is(FAILED));
    }
}
