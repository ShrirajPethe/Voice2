package voice.app.scanner

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import voice.common.BookId
import voice.data.Book
import voice.data.BookContent
import voice.data.Bookmark
import voice.data.Chapter
import voice.data.legacy.LegacyBookMetaData
import voice.data.legacy.LegacyBookSettings
import voice.data.repo.BookContentRepo
import voice.data.repo.internals.dao.BookmarkDao
import voice.data.repo.internals.dao.LegacyBookDao
import voice.data.toUri
import voice.logging.core.Logger
import java.io.File
import java.time.Instant
import javax.inject.Inject

class BookParser
@Inject constructor(
  private val contentRepo: BookContentRepo,
  private val mediaAnalyzer: MediaAnalyzer,
  private val legacyBookDao: LegacyBookDao,
  private val application: Application,
  private val bookmarkDao: BookmarkDao,
) {

  suspend fun parseAndStore(chapters: List<Chapter>, file: DocumentFile): BookContent {
    val id = BookId(file.uri)
    return contentRepo.getOrPut(id) {
      val analyzed = mediaAnalyzer.analyze(chapters.first().id.toUri())

      val filePath = file.uri.filePath()
      val migrationMetaData = filePath?.let {
        legacyBookDao.bookMetaData()
          .find { metadata -> metadata.root.endsWith(it) }
      }
      val migrationSettings = migrationMetaData?.let {
        legacyBookDao.settingsById(it.id)
      }

      if (migrationMetaData != null) {
        migrateBookmarks(migrationMetaData, chapters, id)
      }

      val migratedPlaybackPosition = migrationSettings?.let { findMigratedPlaybackPosition(it, chapters) }

      BookContent(
        id = id,
        isActive = true,
        addedAt = migrationMetaData?.addedAtMillis?.let(Instant::ofEpochMilli)
          ?: Instant.now(),
        author = analyzed?.author,
        lastPlayedAt = migrationSettings?.lastPlayedAtMillis?.let(Instant::ofEpochMilli)
          ?: Instant.EPOCH,
        name = migrationMetaData?.name ?: analyzed?.bookName ?: file.bookName(),
        playbackSpeed = migrationSettings?.playbackSpeed
          ?: 1F,
        skipSilence = migrationSettings?.skipSilence
          ?: false,
        chapters = chapters.map { it.id },
        positionInChapter = migratedPlaybackPosition?.playbackPosition ?: 0L,
        currentChapter = migratedPlaybackPosition?.chapterId ?: chapters.first().id,
        cover = migrationSettings?.id?.let {
          File(application.filesDir, id.toString())
            .takeIf { it.canRead() }
        },
      ).also {
        validateIntegrity(it, chapters)
      }
    }
  }

  private fun findMigratedPlaybackPosition(
    settings: LegacyBookSettings,
    chapters: List<Chapter>,
  ): MigratedPlaybackPosition? {
    val currentChapter = chapters.find {
      val chapterFilePath = it.id.toUri().filePath()
      if (chapterFilePath == null) {
        false
      } else {
        settings.currentFile.absolutePath.endsWith(chapterFilePath)
      }
    } ?: return null
    return MigratedPlaybackPosition(currentChapter.id, settings.positionInChapter)
  }

  private data class MigratedPlaybackPosition(
    val chapterId: Chapter.Id,
    val playbackPosition: Long,
  )

  private suspend fun migrateBookmarks(migrationMetaData: LegacyBookMetaData, chapters: List<Chapter>, id: BookId) {
    val legacyChapters = legacyBookDao.chapters()
      .filter {
        it.bookId == migrationMetaData.id
      }

    legacyBookDao.bookmarksByFiles(legacyChapters.map { it.file })
      .forEach { legacyBookmark ->
        val legacyChapter = legacyChapters.find { it.file == legacyBookmark.mediaFile }
        if (legacyChapter != null) {
          val matchingChapter = chapters.find {
            val chapterFilePath = it.id.toUri().filePath() ?: return@find false
            legacyChapter.file.absolutePath.endsWith(chapterFilePath)
          }
          if (matchingChapter != null) {
            bookmarkDao.addBookmark(
              Bookmark(
                bookId = id,
                addedAt = legacyBookmark.addedAt,
                chapterId = matchingChapter.id,
                id = Bookmark.Id.random(),
                setBySleepTimer = legacyBookmark.setBySleepTimer,
                time = legacyBookmark.time,
                title = legacyBookmark.title,
              ),
            )
          }
        }
      }
  }

  private fun DocumentFile.bookName(): String {
    val fileName = name
    return if (fileName == null) {
      uri.toString()
        .removePrefix("/storage/emulated/0/")
        .removePrefix("/storage/emulated/")
        .removePrefix("/storage/")
        .also {
          Logger.e("Could not parse fileName from $this. Fallback to $it")
        }
    } else {
      if (isFile) {
        fileName.substringBeforeLast(".")
      } else {
        fileName
      }
    }
  }
}

internal fun validateIntegrity(content: BookContent, chapters: List<Chapter>) {
  // the init block performs integrity validation
  Book(content, chapters)
}

private fun Uri.filePath(): String? {
  return pathSegments.lastOrNull()
    ?.dropWhile { it != ':' }
    ?.removePrefix(":")
}
