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

package androidx.paging.integration.testapp.v3room

import androidx.paging.LoadType
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.integration.testapp.room.Customer
import androidx.paging.integration.testapp.room.SampleDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class V3RemoteMediator(
    private val database: SampleDatabase,
    private val networkSource: NetworkCustomerPagingSource
) : RemoteMediator<Int, Customer>() {
    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, Customer>
    ): MediatorResult {
        if (loadType == LoadType.START) return MediatorResult.Success(false)

        // TODO: Move this to be a more fully featured sample which demonstrated key translation
        //  between two types of PagingSources where the keys do not map 1:1.
        val key = when (loadType) {
            LoadType.REFRESH -> 0
            LoadType.START -> throw IllegalStateException()
            LoadType.END -> state.pages.lastOrNull()?.nextKey ?: 0
        }
        val result = networkSource.load(
            PagingSource.LoadParams(
                loadType = loadType,
                key = key,
                loadSize = 10,
                placeholdersEnabled = false,
                pageSize = 10
            )
        )

        return when (result) {
            is PagingSource.LoadResult.Page -> {
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        if (loadType == LoadType.REFRESH) {
                            database.customerDao.removeAll()
                        }

                        database.customerDao.insertAll(result.data.toTypedArray())
                    }
                }

                MediatorResult.Success(canRequestMoreData = true)
            }
            is PagingSource.LoadResult.Error -> {
                MediatorResult.Error(result.throwable)
            }
        }
    }
}