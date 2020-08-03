package me.saket.press.shared.sync

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isTrue
import com.badoo.reaktive.completable.Completable
import com.badoo.reaktive.completable.asSingle
import com.badoo.reaktive.single.blockingGet
import com.soywiz.klock.DateTime
import com.soywiz.klock.hours
import me.saket.kgit.PushResult.Failure
import me.saket.kgit.RealGit
import me.saket.kgit.SshPrivateKey
import me.saket.press.data.shared.Note
import me.saket.press.data.shared.NoteQueries
import me.saket.press.shared.BuildKonfig
import me.saket.press.shared.Platform
import me.saket.press.shared.PlatformHost.Android
import me.saket.press.shared.containsOnly
import me.saket.press.shared.db.BaseDatabaeTest
import me.saket.press.shared.db.NoteId
import me.saket.press.shared.fakedata.fakeNote
import me.saket.press.shared.settings.FakeSetting
import me.saket.press.shared.sync.SyncState.PENDING
import me.saket.press.shared.sync.SyncState.SYNCED
import me.saket.press.shared.sync.git.File
import me.saket.press.shared.sync.git.FileName
import me.saket.press.shared.sync.git.GitSyncer
import me.saket.press.shared.sync.git.GitSyncerConfig
import me.saket.press.shared.sync.git.UtcTimestamp
import me.saket.press.shared.sync.git.service.GitRepositoryInfo
import me.saket.press.shared.testDeviceInfo
import me.saket.press.shared.time.FakeClock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class GitSyncerTest : BaseDatabaeTest() {

  private val deviceInfo = testDeviceInfo()
  private val noteQueries get() = database.noteQueries
  private val git = RealGit()
  private val clock = FakeClock()
  private val config = GitSyncerConfig(
      remote = GitRepositoryInfo(
          name = "ignored",
          url = "ignored",
          sshUrl = BuildKonfig.GIT_TEST_REPO_SSH_URL,
          defaultBranch = BuildKonfig.GIT_TEST_REPO_BRANCH
      ),
      sshKey = SshPrivateKey(BuildKonfig.GIT_TEST_SSH_PRIV_KEY)
  )
  private val configSetting = FakeSetting(config)
  private val syncer = GitSyncer(
      git = git,
      config = configSetting,
      database = database,
      deviceInfo = deviceInfo,
      clock = clock,
      status = FakeSetting(null)
  )

  private fun canRunTests(): Boolean {
    // todo: make tests work for native platforms.
    return BuildKonfig.GIT_TEST_SSH_PRIV_KEY.isNotBlank() && Platform.host == Android
  }

  @BeforeTest
  fun setUp() {
    RemoteRepositoryRobot {
      deleteEverything()
    }
  }

  @AfterTest
  fun cleanUp() {
    deviceInfo.appStorage.delete(recursively = true)

    val unsyncedNotes = noteQueries.allNotes().executeAsList().filter { it.syncState != SYNCED }
    if (unsyncedNotes.isNotEmpty()) {
      println("\nUNSYNCED NOTES FOUND: ")
      unsyncedNotes.forEach { println(it) }
      error("Unsynced notes found on completion of test")
    }
  }

  /*@Test*/ fun `pull notes from a non-empty repo`() {
    if (!canRunTests()) return

    val firstCommitTime = clock.nowUtc() - 10.hours
    val secondCommitTime = clock.nowUtc()

    // Given: Remote repository has some notes over multiple commits.
    RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = firstCommitTime,
          add = listOf(
              "note_1.md" to "# The Witcher",
              "note_2.md" to "# Uncharted: The Lost Legacy"
          )
      )
      commitFiles(
          message = "Second commit",
          time = secondCommitTime,
          add = listOf(
              "note_3.md" to "# Overcooked",
              "note_4.md" to "# The Last of Us"
          )
      )
      forcePush()
    }

    // Given: User hasn't saved any notes on this device yet.
    assertThat(noteQueries.visibleNotes().executeAsList()).isEmpty()

    syncer.sync().blockingAwait()

    // Check that the notes were pulled and saved into DB.
    val notesAfterSync = noteQueries.visibleNotes().executeAsList()
    assertThat(notesAfterSync.map { it.content }).containsOnly(
        "# The Witcher",
        "# Uncharted: The Lost Legacy",
        "# Overcooked",
        "# The Last of Us"
    )

    notesAfterSync.first { it.content == "# The Witcher" }.apply {
      assertThat(createdAt).isEqualTo(firstCommitTime)
      assertThat(updatedAt).isEqualTo(firstCommitTime)
      assertThat(isArchived).isFalse()
      assertThat(isPendingDeletion).isFalse()
    }

    notesAfterSync.first { it.content == "# The Last of Us" }.apply {
      assertThat(createdAt).isEqualTo(secondCommitTime)
    }
  }

  /*@Test*/ fun `push notes to an empty repo`() {
    if (!canRunTests()) return

    // Given: Remote repository is empty.
    val remote = RemoteRepositoryRobot()

    // Given: This device has some notes.
    noteQueries.testInsert(
        fakeNote(
            content = "# Nicolas Cage \nis a national treasure",
            clock = clock
        ),
        fakeNote(
            content = "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures",
            clock = clock
        )
    )

    syncer.sync().blockingAwait()

    // Check that the local note(s) were pushed to remote.
    assertThat(remote.fetchNoteFiles()).containsOnly(
        "nicolas_cage.md" to "# Nicolas Cage \nis a national treasure",
        "witcher_3.md" to "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
    )
  }

  /*@Test*/ fun `pull remote notes with same content but different filename`() {
    if (!canRunTests()) return

    RemoteRepositoryRobot {
      commitFiles(
          message = "Create test.md",
          time = clock.nowUtc(),
          add = listOf(
              "test.md" to "# Test",
              "test2.md" to "# Test"
          )
      )
      forcePush()
    }

    syncer.sync().blockingAwait()

    val localNotes = noteQueries.visibleNotes().executeAsList().map { it.content }
    assertThat(localNotes).containsOnly(
        "# Test",
        "# Test"
    )
  }

  /*@Test*/ fun `merge local and remote notes without conflicts`() {
    if (!canRunTests()) return

    // Given: Remote and local notes are saved in mixed order.
    val remoteTime1 = clock.nowUtc() - 10.hours
    val localTime1 = remoteTime1 + 2.hours
    val remoteTime2 = remoteTime1 + 4.hours
    val localTime2 = clock.nowUtc()

    // Given: Remote repository has some notes over multiple commits.
    val remote = RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = remoteTime1,
          add = listOf("note_1.md" to "# Uncharted: The Lost Legacy")
      )
      commitFiles(
          message = "Second commit",
          time = remoteTime2,
          add = listOf("note_2.md" to "# The Last of Us")
      )
      forcePush()
    }

    // Given: This device has some notes.
    noteQueries.testInsert(
        fakeNote(
            content = "# Nicolas Cage \nis a national treasure",
            updatedAt = localTime1
        ),
        fakeNote(
            content = "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures",
            updatedAt = localTime2
        )
    )

    println("\nNotes in local DB before sync: ")
    noteQueries.visibleNotes()
        .executeAsList()
        .sortedBy { it.updatedAt }
        .forEach { println("${it.id} ${it.content.replace("\n", " ")}") }

    syncer.sync().blockingAwait()

    // Check: both local and remote have same notes with same timestamps.
    val localNotes = noteQueries.visibleNotes()
        .executeAsList()
        .sortedBy { it.updatedAt }

    assertThat(localNotes.map { it.content }).containsExactly(
        "# Uncharted: The Lost Legacy",
        "# Nicolas Cage \nis a national treasure",
        "# The Last of Us",
        "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
    )

    assertThat(remote.fetchNoteFiles()).containsOnly(
        "note_1.md" to "# Uncharted: The Lost Legacy",
        "note_2.md" to "# The Last of Us",
        "nicolas_cage.md" to "# Nicolas Cage \nis a national treasure",
        "witcher_3.md" to "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
    )
  }

  /*@Test*/ fun `merge local and remote notes with content conflict`() {
    if (!canRunTests()) return

    clock.rewindTimeBy(10.hours)

    // Given: a note was created on another device.
    val remote = RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = clock.nowUtc(),
          add = listOf("uncharted.md" to "# Uncharted")
      )
      forcePush()
    }
    syncer.sync().blockingAwait()

    // Given: the same note was edited locally.
    val locallyEditedNote = noteQueries.visibleNotes().executeAsOne()
    clock.advanceTimeBy(1.hours)
    noteQueries.updateContent(
        id = locallyEditedNote.id,
        content = "# Uncharted\nLocal edit",
        updatedAt = clock.nowUtc()
    )

    // Given: the same note was edited on remote in a conflicting way.
    clock.advanceTimeBy(1.hours)
    with(remote) {
      commitFiles(
          message = "Second commit",
          time = clock.nowUtc(),
          add = listOf("uncharted.md" to "# Uncharted\nRemote edit")
      )
      forcePush()
    }

    // The conflict should get auto-resolved here.
    syncer.sync().blockingAwait()

    val localNotes = noteQueries.visibleNotes().executeAsList().sortedBy { it.updatedAt }

    // The local note should get duplicated as a new note and then
    // the local note should get overridden by the server copy.
    assertThat(localNotes).hasSize(2)
    assertThat(localNotes[0].content).isEqualTo("# Uncharted\nRemote edit")
    assertThat(localNotes[0].id).isEqualTo(locallyEditedNote.id)

    assertThat(localNotes[1].content).isEqualTo("# Uncharted\nLocal edit")
    assertThat(localNotes[1].id).isNotEqualTo(locallyEditedNote.id)
  }

  @Test fun `merge local and remote notes with delete conflict`() {
    if (!canRunTests()) return

    clock.rewindTimeBy(10.hours)

    // Given: a note was created on another device.
    val remote = RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = clock.nowUtc(),
          add = listOf("uncharted.md" to "# Uncharted")
      )
      forcePush()
    }
    syncer.sync().blockingAwait()

    // Given: the same note was renamed locally, effectively DELETING the file.
    val locallyEditedNote = noteQueries.visibleNotes().executeAsOne()
    clock.advanceTimeBy(1.hours)
    noteQueries.updateContent(
        id = locallyEditedNote.id,
        content = "# Uncharted2\nLocal edit",
        updatedAt = clock.nowUtc()
    )

    // Given: the same note was edited on remote
    clock.advanceTimeBy(1.hours)
    with(remote) {
      pull()
      commitFiles(
          message = "Second commit",
          time = clock.nowUtc(),
          add = listOf("uncharted.md" to "# Uncharted\nRemote edit")
      )
      forcePush()
    }

    // The conflict should get auto-resolved here.
    syncer.sync().blockingAwait()

    val localNotes = noteQueries.visibleNotes().executeAsList().sortedBy { it.updatedAt }

    // The local note should get duplicated as a new note and then
    // the local note should get overridden by the server copy.
    assertThat(localNotes).hasSize(2)
    assertThat(localNotes[0].content).isEqualTo("# Uncharted\nRemote edit")
    assertThat(localNotes[0].id).isEqualTo(locallyEditedNote.id)

    assertThat(localNotes[1].content).isEqualTo("# Uncharted2\nLocal edit")
    assertThat(localNotes[1].id).isNotEqualTo(locallyEditedNote.id)

//    clock.rewindTimeBy(10.hours)
//
//    val remote = RemoteRepositoryRobot {
//      commitFiles(
//          message = "First commit",
//          time = clock.nowUtc(),
//          add = listOf("uncharted.md" to "# Uncharted")
//      )
//      forcePush()
//    }
//    syncer.sync().blockingAwait()
//
//    clock.advanceTimeBy(1.hours)
//    val locallyEditedNote = noteQueries.visibleNotes().executeAsOne()
//    noteQueries.updateContent(
//        id = locallyEditedNote.id,
//        content = "# Uncharted2",
//        updatedAt = clock.nowUtc()
//    )
//
//    clock.advanceTimeBy(2.hours)
//    with(remote) {
//      pull()
//      commitFiles(
//          message = "Create 'test.md' on remote",
//          time = clock.nowUtc(),
//          add = listOf("test.md" to "# Test")
//      )
//      forcePush()
//    }
//
//    clock.advanceTimeBy(2.hours)
//    noteQueries.updateContent("# Test2", updatedAt = clock.nowUtc(), id = note.id)
//    syncer.sync().blockingAwait()
//
//    val localNotes = noteQueries.visibleNotes().executeAsList().map { it.content }
//    assertThat(localNotes).containsOnly(
//        "# Test",
//        "# Test2"
//    )
  }

  // TODO
  /*@Test*/ fun `merge local and remote notes with filename and content conflict`() {
    if (!canRunTests()) return
  }

  /*@Test*/ fun `notes with the same headings are stored in separate files`() {
    if (!canRunTests()) return

    val now = clock.nowUtc()
    noteQueries.testInsert(
        fakeNote(updatedAt = now + 1.hours, content = "# Shopping List\nMangoes and strawberries"),
        fakeNote(updatedAt = now + 2.hours, content = "# Shopping List\nMilk and eggs"),
        fakeNote(updatedAt = now + 3.hours, content = "Note without heading"),
        fakeNote(updatedAt = now + 4.hours, content = "Another note without heading")
    )
    syncer.sync().blockingAwait()

    val remoteFiles = RemoteRepositoryRobot().fetchNoteFiles()
    assertThat(remoteFiles).containsOnly(
        "shopping_list.md" to "# Shopping List\nMangoes and strawberries",
        "shopping_list_2.md" to "# Shopping List\nMilk and eggs",
        "untitled_note.md" to "Note without heading",
        "untitled_note_2.md" to "Another note without heading"
    )
  }

  /*@Test*/ fun `sync notes deleted on remote`() {
    if (!canRunTests()) return

    noteQueries.insert(
        id = NoteId.generate(),
        content = "# Horizon Zero Dawn",
        createdAt = clock.nowUtc(),
        updatedAt = clock.nowUtc()
    )
    syncer.sync().blockingAwait()

    val savedNotes = { noteQueries.allNotes().executeAsList() }
    assertThat(savedNotes()).isNotEmpty()

    clock.advanceTimeBy(2.hours)
    RemoteRepositoryRobot {
      pull()
      commitFiles(
          message = "Delete notes",
          time = clock.nowUtc(),
          delete = listOf("horizon_zero_dawn.md")
      )
      forcePush()
    }
    syncer.sync().blockingAwait()

    assertThat(savedNotes()).isEmpty()
  }

  // TODO
  /*@Test*/ fun `sync notes deleted locally`() {
    if (!canRunTests()) return
  }

  // TODO
  /*@Test*/ fun `filename-register from remote is used for determining file name`() {
    if (!canRunTests()) return
  }

  /*@Test*/ fun `ignore notes that are already synced`() {
    if (!canRunTests()) return

    noteQueries.testInsert(
        fakeNote(content = "# The Last of Us II", syncState = SYNCED, clock = clock),
        fakeNote(content = "# Horizon Zero Dawn", syncState = PENDING, clock = clock)
    )
    syncer.sync().blockingAwait()

    val remoteFiles = RemoteRepositoryRobot().fetchNoteFiles()
    assertThat(remoteFiles).containsOnly(
        "horizon_zero_dawn.md" to "# Horizon Zero Dawn"
    )
  }

  /*@Test*/ fun `sync notes with renamed heading`() {
    if (!canRunTests()) return

    val noteId = NoteId.generate()
    noteQueries.testInsert(
        fakeNote(
            id = noteId,
            content = """
            |# John
            |I'm thinking I'm back
            """.trimMargin()
        )
    )
    syncer.sync().blockingAwait()

    val remote = RemoteRepositoryRobot()
    assertThat(remote.fetchNoteFiles()).containsOnly(
        "john.md" to "# John\nI'm thinking I'm back"
    )

    noteQueries.updateContent(
        id = noteId,
        updatedAt = clock.nowUtc(),
        content =
        """
        |# John Wick
        |I'm thinking I'm back
        """.trimMargin()
    )
    syncer.sync().blockingAwait()

    assertThat(remote.fetchNoteFiles()).containsOnly(
        "john_wick.md" to "# John Wick\nI'm thinking I'm back"
    )
  }

  /*@Test*/ fun `sync archived notes`() {
    if (!canRunTests()) return

    val note1 = fakeNote("# Horizon Zero Dawn")
    val note2 = fakeNote("# Uncharted")
    noteQueries.testInsert(note1, note2)
    syncer.sync().blockingAwait()

    // Archive both notes: one on local and the other on remote.
    noteQueries.markAsArchived(id = note1.id, updatedAt = clock.nowUtc())
    val remote = RemoteRepositoryRobot {
      pull()
      commitFiles(
          message = "Archive uncharted.md",
          rename = listOf(
              "uncharted.md" to "archived/uncharted.md"
          )
      )
      forcePush()
    }
    syncer.sync().blockingAwait()

    val notes = noteQueries.allNotes().executeAsList()
    println("Local notes after syncing:"); notes.forEach { println(it) }
    assertThat(notes.all { it.isArchived }).isTrue()

    assertThat(remote.fetchNoteFiles()).containsOnly(
        "archived/horizon_zero_dawn.md" to "# Horizon Zero Dawn",
        "archived/uncharted.md" to "# Uncharted"
    )
  }

  /*@Test*/ fun `file renames are followed correctly`() {
    if (!canRunTests()) return

    noteQueries.insert(
        id = NoteId.generate(),
        content = "# Horizon Zero Dawn",
        createdAt = clock.nowUtc(),
        updatedAt = clock.nowUtc()
    )
    syncer.sync().blockingAwait()

    // kgit should be able to identify an ADD + DELETE as a RENAME.
    RemoteRepositoryRobot {
      pull()
      commitFiles(
          message = "Delete notes",
          time = clock.nowUtc(),
          delete = listOf("horizon_zero_dawn.md"),
          add = listOf("archived/horizon_zero_dawn.md" to "# Horizon Zero Dawn")
      )
      forcePush()
    }
    syncer.sync().blockingAwait()

    val savedNote = noteQueries.allNotes().executeAsOne()
    assertThat(savedNote.isArchived).isTrue()
  }

  /*@Test*/ fun `notes are re-synced when syncing is re-enabled`() {
    val note = fakeNote(content = "# Potter\nYou're a wizard Harry", clock = clock)
    noteQueries.testInsert(note)

    RemoteRepositoryRobot().let { remote1 ->
      syncer.sync().blockingAwait()
      assertThat(remote1.fetchNoteFiles()).containsOnly("potter.md" to "# Potter\nYou're a wizard Harry")
      remote1.deleteEverything()
    }

    syncer.disable().blockingAwait()

    RemoteRepositoryRobot().let { remote2 ->
      assertThat(remote2.fetchNoteFiles()).isEmpty()

      configSetting.set(config)
      syncer.sync().blockingAwait()
      assertThat(remote2.fetchNoteFiles()).containsOnly(
          "potter.md" to "# Potter\nYou're a wizard Harry"
      )
    }
  }

  private inner class RemoteRepositoryRobot(prepare: RemoteRepositoryRobot.() -> Unit = {}) {
    private val directory = File(deviceInfo.appStorage, "temp").apply { makeDirectory() }
    private val gitRepo = git.repository(config.sshKey, directory.path)

    init {
      gitRepo.addRemote("origin", config.remote.sshUrl)
      gitRepo.commitAll("Initial commit", timestamp = UtcTimestamp(clock), allowEmpty = true)
      gitRepo.checkout(config.remote.defaultBranch)
      prepare()
    }

    fun pull() {
      gitRepo.pull(rebase = true)
    }

    fun forcePush() {
      assertThat(gitRepo.push(force = true)).isNotInstanceOf(Failure::class)
    }

    fun commitFiles(
      message: String,
      time: DateTime = clock.nowUtc(),
      add: List<Pair<FileName, FileName>> = emptyList(),
      delete: List<FileName> = emptyList(),
      rename: List<Pair<FileName, String>> = emptyList()
    ) {
      add.forEach { (name, body) ->
        File(directory, name).apply {
          parent!!.makeDirectory(recursively = true)
          write(body)
        }
      }
      delete.forEach { path ->
        File(directory, path).delete()
      }
      rename.forEach { (oldPath, newPath) ->
        File(directory, oldPath).renameTo(File(directory, newPath))
      }
      gitRepo.commitAll(message, timestamp = UtcTimestamp(time), allowEmpty = true)
    }

    /**
     * @return Pair(relative file path, file content).
     */
    fun fetchNoteFiles(): List<Pair<String, String>> {
      gitRepo.pull(rebase = true)
      return directory
          .children(recursively = true)
          .filter { it.extension == "md" && !it.relativePathIn(directory).startsWith(".") }
          .map { it.relativePathIn(directory) to it.read() }
    }

    fun deleteEverything() {
      directory.children().filter { it.name != ".git" }.forEach { it.delete(recursively = true) }
      commitFiles(message = "Emptiness", add = emptyList())
      forcePush()
      directory.delete(recursively = true)
    }
  }
}

private fun NoteQueries.testInsert(vararg notes: Note) {
  notes.forEach { testInsert(it) }
}

private fun Completable.blockingAwait() {
  return asSingle(Unit).blockingGet()
}
