/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.leanback.paging

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.TestPagingSource
import androidx.paging.assertEvents
import androidx.paging.localLoadStatesOf
import androidx.paging.toCombinedLoadStatesLocal
import androidx.recyclerview.widget.DiffUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScope
import androidx.testutils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.coroutines.ContinuationInterceptor
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.paging.ExperimentalPagingApi
import androidx.test.filters.SmallTest
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PagingDataAdapterTest {

    private val testScope = TestCoroutineScope()

    @get:Rule
    val dispatcherRule = MainDispatcherRule(
        testScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    )

    /*
     * Testing get(), size()
     */
    @Test
    fun testGetItem() = testScope.runBlockingTest {
        val pagingSource = TestPagingSource()
        val pagingDataAdapter =
            PagingDataAdapter(diffCallback = object : DiffUtil.ItemCallback<Int>() {
                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
        }, workerDispatcher = Dispatchers.Main)
        val refreshEvents = mutableListOf<Boolean>()
        pagingDataAdapter.addDataRefreshListener { refreshEvents.add(it) }
        val pager = Pager(
            config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2
            ),
            initialKey = 50
        ) {
            pagingSource
        }
        val job = launch {
            pager.flow.collect {
                pagingDataAdapter.submitData(it)
            }
        }
        advanceUntilIdle()
        job.cancel()
        assertEquals(null, pagingDataAdapter.get(90))
        assertEquals(pagingSource.items.get(51), pagingDataAdapter.get(51))
        assertEquals(pagingSource.items.size, pagingDataAdapter.size())
    }

    /*
     * Testing dataRefreshListener callbacks
     */
    @Test
    fun testDataRefreshListenerCallbacks() = testScope.runBlockingTest {
        val pagingDataAdapter =
            PagingDataAdapter(diffCallback = object : DiffUtil.ItemCallback<Int>() {
                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
        }, workerDispatcher = Dispatchers.Main)
        val refreshEvents = mutableListOf<Boolean>()
        pagingDataAdapter.addDataRefreshListener { refreshEvents.add(it) }
        val pager = Pager(
            config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2
            ),
            initialKey = 50
        ) {
            TestPagingSource()
        }
        val job = launch {
            pager.flow.collect {
                pagingDataAdapter.submitData(it)
            }
        }
        advanceUntilIdle()
        pagingDataAdapter.get(51)
        advanceUntilIdle()
        pagingDataAdapter.get(52)
        assertEquals(pagingDataAdapter.size(), 100)
        job.cancel()
        pagingDataAdapter.submitData(TestLifecycleOwner().lifecycle, PagingData.empty<Int>())
        advanceUntilIdle()
        assertEvents(expected = listOf(false, true), actual = refreshEvents)
    }

    /*
     * Testing loadStateListener callbacks
     */
    @Test
    fun testLoadStateListenerCallbacks() = testScope.runBlockingTest {
        val pagingDataAdapter =
            PagingDataAdapter(diffCallback = object : DiffUtil.ItemCallback<Int>() {
                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
        }, workerDispatcher = Dispatchers.Main)
        val loadEvents = mutableListOf<CombinedLoadStates>()
        pagingDataAdapter.addLoadStateListener { loadEvents.add(it) }
        val pager = Pager(
            config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2
            ),
            initialKey = 50
        ) {
            TestPagingSource()
        }
        val job = launch {
            pager.flow.collect {
                pagingDataAdapter.submitData(it)
            }
        }
        advanceUntilIdle()
        // Assert that all load state updates are sent, even when differ enters fast path for
        // empty previous list.
        assertEvents(
            listOf(
                LoadType.REFRESH to LoadState.Loading,
                LoadType.REFRESH to LoadState.NotLoading(endOfPaginationReached = false)
            ).toCombinedLoadStatesLocal(),
            loadEvents
        )
        loadEvents.clear()
        job.cancel()
        pagingDataAdapter.submitData(TestLifecycleOwner().lifecycle, PagingData.empty<Int>())
        advanceUntilIdle()
        // Assert that all load state updates are sent, even when differ enters fast path for
        // empty next list.
        assertEvents(
            expected = listOf(
                localLoadStatesOf(
                    refreshLocal = LoadState.NotLoading(endOfPaginationReached = false),
                    prependLocal = LoadState.NotLoading(endOfPaginationReached = true),
                    appendLocal = LoadState.NotLoading(endOfPaginationReached = false)
                ),
                localLoadStatesOf(
                    refreshLocal = LoadState.NotLoading(endOfPaginationReached = false),
                    prependLocal = LoadState.NotLoading(endOfPaginationReached = true),
                    appendLocal = LoadState.NotLoading(endOfPaginationReached = true)
                )
            ),
            actual = loadEvents
        )
    }
}
