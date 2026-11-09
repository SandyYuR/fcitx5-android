/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.testing.hostDatabaseContext
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClipboardSearchPagingTest {
    private lateinit var database: ClipboardDatabase
    private val dao get() = database.clipboardDao()

    @Before fun setup() {
        val executor = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(hostDatabaseContext(appContext), ClipboardDatabase::class.java)
            .setQueryExecutor(executor).setTransactionExecutor(executor)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(ClipboardManager::class.java, "clbDao", dao)
    }
    @After fun close() { database.close() }

    private suspend fun page(category: ClipboardCategory, query: String = ""): List<ClipboardEntry> {
        val result = ClipboardManager.entries(category, query).load(PagingSource.LoadParams.Refresh(null, 48, false))
        return (result as PagingSource.LoadResult.Page<Int, ClipboardEntry>).data
    }

    @Test fun emptyQueriesAndSearchUseTheSameCategoryBoundariesAndOrdering() = runBlocking {
        val entries = listOf(
            ClipboardEntry(id = 1, text = "needle local", timestamp = 1),
            ClipboardEntry(id = 2, text = "needle pinned", pinned = true, timestamp = 2),
            ClipboardEntry(id = 3, text = "needle remote", source = ClipboardEntry.SOURCE_REMOTE, timestamp = 3),
            ClipboardEntry(id = 4, text = "needle remote pinned", source = ClipboardEntry.SOURCE_REMOTE, pinned = true, timestamp = 4),
            ClipboardEntry(id = 5, text = "content://media/needle.png", type = "image/png", timestamp = 5),
            ClipboardEntry(id = 6, text = "file:///needle.pdf", source = ClipboardEntry.SOURCE_REMOTE, timestamp = 6),
            ClipboardEntry(id = 7, text = "needle deleted", deleted = true, timestamp = 7),
            ClipboardEntry(id = 8, text = "other local", timestamp = 8),
        )
        entries.forEach { dao.insert(it) }
        val expected = mapOf(
            ClipboardCategory.All to listOf(4, 2, 8, 6, 5, 3, 1),
            ClipboardCategory.Favorites to listOf(4, 2),
            ClipboardCategory.Local to listOf(8, 1),
            ClipboardCategory.Media to listOf(6, 5),
            ClipboardCategory.Remote to listOf(4, 6, 3),
        )
        for ((category, ids) in expected) {
            assertEquals(category.name, ids, page(category).map { it.id })
            assertEquals(category.name, ids.filter { it != 8 }, page(category, "NEEDLE").map { it.id })
        }
    }

    @Test fun literalWildcardsBackslashesAndOriginalTextRemainSearchable() = runBlocking {
        dao.insert(ClipboardEntry(id = 1, text = "50%_\\账单"))
        dao.insert(ClipboardEntry(id = 2, text = "50xY账单"))
        dao.insert(ClipboardEntry(id = 3, text = "cleaned", originalText = "HTTPS://example.test/Needle"))
        for (query in listOf("%", "_", "\\", "%_\\")) {
            assertEquals(query, listOf(1), page(ClipboardCategory.All, query).map { it.id })
        }
        assertEquals(listOf(3), page(ClipboardCategory.All, "needle").map { it.id })
        assertTrue(page(ClipboardCategory.Remote, "needle").isEmpty())
    }

    @Test fun everyMatchCanBePagedAndPinDeleteChangesInvalidateTheSource() = runBlocking {
        for (id in 1..260) dao.insert(ClipboardEntry(id = id, text = "match-$id", timestamp = id.toLong()))
        val source = ClipboardManager.entries(ClipboardCategory.All, "match")
        var result = source.load(PagingSource.LoadParams.Refresh(null, 48, false)) as PagingSource.LoadResult.Page<Int, ClipboardEntry>
        val ids = result.data.map { it.id }.toMutableList()
        while (result.nextKey != null) {
            result = source.load(PagingSource.LoadParams.Append(result.nextKey!!, 16, false)) as PagingSource.LoadResult.Page<Int, ClipboardEntry>
            ids += result.data.map { it.id }
        }
        assertEquals((260 downTo 1).toList(), ids)
        dao.updatePinStatus(1, true)
        database.invalidationTracker.refreshVersionsSync()
        assertTrue(source.invalid)
        assertEquals(1, page(ClipboardCategory.All, "match").first().id)
        dao.markAsDeleted(1)
        assertTrue(page(ClipboardCategory.Favorites, "match").isEmpty())
    }
}
