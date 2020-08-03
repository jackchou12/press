package me.saket.press.shared.sync.git

import com.badoo.reaktive.completable.completableFromFunction
import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.combineLatest
import com.soywiz.klock.DateTime
import kotlinx.coroutines.Runnable
import me.saket.kgit.Git
import me.saket.kgit.GitAuthor
import me.saket.kgit.GitCommit
import me.saket.kgit.GitConfig
import me.saket.kgit.GitTreeDiff
import me.saket.kgit.GitTreeDiff.Change.Add
import me.saket.kgit.GitTreeDiff.Change.Copy
import me.saket.kgit.GitTreeDiff.Change.Delete
import me.saket.kgit.GitTreeDiff.Change.Modify
import me.saket.kgit.GitTreeDiff.Change.Rename
import me.saket.kgit.MergeConflict
import me.saket.kgit.MergeStrategy.OURS
import me.saket.kgit.PullResult
import me.saket.kgit.PushResult.Failure
import me.saket.kgit.RebaseResult
import me.saket.kgit.UtcTimestamp
import me.saket.kgit.abbreviated
import me.saket.press.PressDatabase
import me.saket.press.shared.db.NoteId
import me.saket.press.shared.home.SplitHeadingAndBody
import me.saket.press.shared.settings.Setting
import me.saket.press.shared.sync.SyncState
import me.saket.press.shared.sync.SyncState.IN_FLIGHT
import me.saket.press.shared.sync.SyncState.PENDING
import me.saket.press.shared.sync.SyncState.SYNCED
import me.saket.press.shared.sync.Syncer
import me.saket.press.shared.sync.Syncer.Status.Disabled
import me.saket.press.shared.sync.Syncer.Status.Failed
import me.saket.press.shared.sync.Syncer.Status.Idle
import me.saket.press.shared.sync.Syncer.Status.InFlight
import me.saket.press.shared.sync.git.FileNameRegister.OnRenameListener
import me.saket.press.shared.sync.git.GitSyncer.Result.DONE
import me.saket.press.shared.sync.git.GitSyncer.Result.SKIPPED
import me.saket.press.shared.sync.git.service.GitRepositoryInfo
import me.saket.press.shared.time.Clock
import me.saket.wysiwyg.atomicLazy

// TODO:
//  Stop ship
//   - broadcast an event when a merge conflict is resolved.
//  Others
//   - figure out git author name/email.
//   - set both author and committer time.
//   - commit deleted notes.
//   - show errors in status UI
class GitSyncer(
  git: Git,
  private val config: Setting<GitSyncerConfig>,
  private val database: PressDatabase,
  private val deviceInfo: DeviceInfo,
  private val clock: Clock,
  private val status: Setting<Status>
) : Syncer() {

  private val noteQueries get() = database.noteQueries
  private val directory = File(deviceInfo.appStorage, "git")
  private val register = FileNameRegister(directory)
  private val gitAuthor = GitAuthor("Saket", "pressapp@saket.me")
  private val remote: GitRepositoryInfo? get() = config.get()?.remote
  val loggers = SyncLoggers(PrintLnSyncLogger)

  // Lazy to avoid reading anything on the main thread.
  private val git by atomicLazy {
    with(config.get()!!) {
      git.repository(sshKey = sshKey, path = directory.path).apply {
        addRemote("origin", remote.sshUrl)
      }
    }
  }

  private enum class Result {
    DONE,
    SKIPPED
  }

  override fun status(): Observable<Status> {
    return combineLatest(config.listen(), status.listen()) { config, status ->
      when (config) {
        null -> Disabled
        else -> status ?: Idle(lastSyncedAt = null)
      }
    }
  }

  override fun sync() = completableFromFunction {
    val rollBackToStatus = status.get()
//    if (rollBackToStatus !is Idle) {
//      // Multiple syncs?
//      return@completableFromFunction
//    }

    status.set(InFlight)
    loggers.onSyncStart()

    try {
      git.maybeInit(config = {
        GitConfig("diff" to listOf("renames" to "true"))
      }
      )

      directory.makeDirectory(recursively = true)
      maybeMakeInitialCommit()

      val commitResult = commitAllChanges()
      val pullResult = pull()

      if (commitResult == DONE || pullResult == DONE) {
        push()
      }
      check(!git.isStagingAreaDirty())

      status.set(Idle(lastSyncedAt = clock.nowUtc()))

    } catch (e: Throwable) {
      println("Caught error: ${e.message}")
      status.set(Failed)
      throw e

    } finally {
      loggers.onSyncComplete()
    }
  }

  override fun disable() = completableFromFunction {
    config.set(null)
    directory.delete(recursively = true)
    noteQueries.swapSyncStates(old = SyncState.values().toList(), new = PENDING)
  }

  /** Commit announcing that syncing has been setup. */
  private fun maybeMakeInitialCommit() {
    if (git.headCommit() != null) {
      return
    }

    with(File(directory, ".press/")) {
      makeDirectory(recursively = true)
      File(this, "README.md").write(
          "Press uses files in this directory for storing meta-data of your synced notes. " +
              "They are auto-generated and shouldn't be modified. If you run into any " +
              "issues with syncing of notes, feel free to file a [bug report here]" +
              "(https://github.com/saket/press/issues) and attach [sync logs](sync_log.txt)" +
              " after removing/redacting any private info."
      )
    }

    git.commitAll(
        message = "Setup syncing on '${deviceInfo.deviceName()}'",
        author = gitAuthor,
        timestamp = UtcTimestamp(clock),
        allowEmpty = true
    )

    // JGit doesn't offer a way to set the initial branch name and it
    // won't let us change the branch without committing anything either
    // so we change it after committing something.
    git.checkout(remote!!.defaultBranch, create = true)
  }

  private fun commitAllChanges(): Result {
    val pendingSyncNotes = noteQueries.pendingSyncNotes().executeAsList()
    if (pendingSyncNotes.isEmpty()) {
      log("Nothing to commit")
      return SKIPPED
    }

    // Having an intermediate sync state between PENDING and SYNCED
    // is important in case a note gets updated while it is syncing,
    // in which case it'll get marked as PENDING again.
    noteQueries.updateSyncState(
        ids = pendingSyncNotes.map { it.id },
        syncState = IN_FLIGHT
    )

    log("Reading unsynced notes:")

    for (note in pendingSyncNotes) {
      val noteFile = register.fileFor(note, renameListener = object : OnRenameListener {
        override fun onRename(oldName: String, newName: String) {
          // Git identifies renames implicitly at runtime rather than explicitly.
          // It applies heuristic by comparing file names and content and tries to
          // guess renames, but it can possibly fail to do so and show them as
          // different files which will result in Press duplicating the notes.
          // Try to help git by committing renames separately.
          log(" • renaming '$oldName' → '$newName'")
          git.commitAll(
              message = "Rename '$oldName' → '$newName'",
              author = gitAuthor,
              timestamp = UtcTimestamp(note.updatedAt)
          )
        }
      })

      log(" • committing ${noteFile.name} (heading: '${SplitHeadingAndBody.split(note.content).first}')")

      noteFile.write(note.content)
      check(git.isStagingAreaDirty())

      git.commitAll(
          message = "Update '${noteFile.name}'",
          author = gitAuthor,
          timestamp = UtcTimestamp(note.updatedAt)
      )
    }
    return DONE
  }

  private fun pull(): Result {
    git.fetch()
    val localHead = git.headCommit()!!  // non-null because of maybeMakeInitialCommit().
    val upstreamHead = git.headCommit(onBranch = "origin/${git.currentBranch().name}")

    log("\nFetching upstream. Local head: $localHead, upstream head: $upstreamHead")
    if (localHead == upstreamHead || upstreamHead == null) {
      // Nothing to fetch.
      log("Nothing to pull")
      return SKIPPED
    }

    // Git makes it easy to handle merge conflicts, but automating it for
    // the user is going to be a challenge. If the same note was modified
    // from different devices, Press duplicates them: note.md & note_2.md.
    // This will also cover non-.md files.
    //
    // It's _very_ important that the local copy is duplicated. It'd be
    // nice to rename the upstream copy because it's possible that the
    // local copy is being edited right now, but duplicating the remote
    // copy will result in an infinite loop where a new copy is created
    // on every sync on the other device.
    //
    // These files will get processed after rebase.
    val conflicts = git.mergeConflicts(with = upstreamHead).filterNoteConflicts()
    if (conflicts.isNotEmpty()) {
      log("\nAuto-resolving merge conflicts: ")

      for (conflict in conflicts) {
        val conflictingNote = File(directory, conflict.path)
        if (conflictingNote.exists) {
          val newName = register.findNewNameOnConflict(conflictingNote)
          conflictingNote.renameTo(newName)
          log(" • ${conflictingNote.relativePathIn(directory)} → $newName")

        } else {
          File(directory, conflict.path).touch()

          // File was possibly renamed locally, but remote continued editing it.
          log(" • ${conflictingNote.relativePathIn(directory)} → skipped")
        }
      }

      if (git.isStagingAreaDirty()) {
        git.commitAll(
            message = "Auto-resolve merge conflicts",
            author = gitAuthor,
            timestamp = UtcTimestamp(clock)
        )
      }
    }

    printRegisters()
//    val rebaseResult = git.rebase(with = upstreamHead, strategy = OURS)
//    if (rebaseResult is RebaseResult.Failure) {
//      rebaseResult.abort()
//      throw error("Failed to rebase: $rebaseResult")
//    }
    val pullResult = git.merge(with = upstreamHead)
    if (pullResult is PullResult.Failure) {
      pullResult.abort()
      throw error("Failed to pull: $pullResult")
    }

    printRegisters()

    // A rebase will cause the history to be re-written, so we need
    // to find the first common ancestor of local and upstream. All
    // changes from the ancestor to the current HEAD will have to be
    // (re)processed.
    processNotesFromCommits(
        from = git.commonAncestor(localHead, upstreamHead),
        to = git.headCommit()!!
    )
    return DONE
  }

  fun printRegisters() {
    val registersDir = File(directory, ".press/registers")
    val registers = registersDir.children(recursively = true).joinToString { it.relativePathIn(registersDir) }
    log("\nRegister files: $registers\n")
  }

  private fun processNotesFromCommits(from: GitCommit?, to: GitCommit) {
    log("\nProcessing commits from ${from?.sha1?.abbreviated} to ${to.sha1.abbreviated}")

    val commits = git.commitsBetween(from = from, toInclusive = to)
    commits.forEach { log(" • ${it.sha1.abbreviated} - ${it.message.lines().first()}") }

    // Press stores updated-at timestamp of notes in each commit.
    val diffPathTimestamps = commits
        .flatMap { commit ->
          git.changesIn(commit)
              .filterNoteChanges()
              .map { it.path to commit.dateTime }
        }
        .toMap()

    // DB operations are executed in one go to
    // avoid locking the DB in a transaction for long.
    val dbOperations = mutableListOf<Runnable>()

    val diffs = git.diffBetween(from, to)

    log("\nChanges (${diffs.size}):")
    if (diffs.isNotEmpty()) log(diffs.joinToString(prefix = " • ", separator = "\n • ", postfix = "\n"))

    for (diff in diffs.filterNoteChanges()) {
      val commitTime = diffPathTimestamps[diff.path]!!

      dbOperations += when (diff) {
        is Copy,
        is Rename,
          // Renaming of note files are ignored. Press
          // generates a name as per the note's heading.
        is Add, is Modify -> {
          val file = File(directory, diff.path)
          val content = file.read()

          val oldPath = if (diff is Rename) diff.fromPath else null
          val record = register.recordFor(diff.path, oldPath = oldPath)
          val existingId = record?.noteId
          val isArchived = record?.noteFolder == "archived"

//          if (diff is Add) {
//            check(existingId == null) {
//              "record: $record, existingId: $existingId"
//            }
//          }

          if (existingId != null) {
            log("Updating $existingId (${diff.path}), isArchived? $isArchived")
            Runnable {
              noteQueries.updateContent(
                  id = existingId,
                  content = content,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = existingId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(existingId),
                  syncState = SYNCED
              )
            }
          } else {
            val newId = NoteId.generate()
            log("Creating new note $newId for (${diff.path}), isArchived? $isArchived")
            register.createNewRecordFor(file, newId)
            Runnable {
              noteQueries.insert(
                  id = newId,
                  content = content,
                  createdAt = commitTime,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = newId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(newId),
                  syncState = SYNCED
              )
            }
          }
        }
        is Delete -> {
          val noteId = register.noteIdFor(diff.path)
          if (noteId == null) {
            // Commit has already been processed earlier.
            Runnable {}
          } else {
            log("Permanently deleting $noteId (${diff.path})")
            Runnable {
              noteQueries.markAsPendingDeletion(noteId)
              noteQueries.updateSyncState(ids = listOf(noteId), syncState = IN_FLIGHT)
              noteQueries.deleteNote(noteId)
            }
          }
        }
      }
    }

    if (dbOperations.isNotEmpty()) {
      noteQueries.transaction {
        dbOperations.forEach { it.run() }
      }
    }

    val savedNotes = noteQueries.allNotes().executeAsList()
    register.pruneStaleRecords(savedNotes)
    if (git.isStagingAreaDirty()) {
      git.commitAll(
          message = "Update file name records",
          author = gitAuthor,
          timestamp = UtcTimestamp(clock)
      )
    }
  }

  private fun List<GitTreeDiff.Change>.filterNoteChanges() = filter { diff ->
    val path = diff.path
    when {
      !path.endsWith(".md") -> false                                        // Not a markdown note.
      path.startsWith(".press/") -> false                                   // Meta-files, ignore.
      path.contains("/") -> {
        when {
          path.startsWith("archived/") && !path.hasMultipleOf('/') -> true  // Archived note.
          else -> error("Folders aren't supported yet: '$path'")
        }
      }
      else -> true
    }
  }

  private fun List<MergeConflict>.filterNoteConflicts() = filter { diff ->
    val path = diff.path
    when {
      path.startsWith(".press/") -> false   // Meta-files, ignore.
      else -> path.endsWith(".md")
    }
  }

  private fun push() {
    val pushResult = git.push()
    require(pushResult !is Failure) { "Failed to push: $pushResult" }
    noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = SYNCED)
  }

  private fun log(message: String) = loggers.log(message)
}

@Suppress("FunctionName")
fun UtcTimestamp(time: DateTime): UtcTimestamp {
  return UtcTimestamp(time.unixMillisLong)
}

@Suppress("FunctionName")
fun UtcTimestamp(clock: Clock): UtcTimestamp {
  return UtcTimestamp(clock.nowUtc())
}

private val GitCommit.dateTime: DateTime
  get() = DateTime.fromUnix(utcTimestamp.millis)
