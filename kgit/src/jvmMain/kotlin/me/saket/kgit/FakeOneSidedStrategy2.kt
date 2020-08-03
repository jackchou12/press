package me.saket.kgit

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.Merger
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.merge.StrategyResolve
import java.io.IOException
import org.eclipse.jgit.merge.MergeStrategy as JgitMergeStrategy

/**
 * A "ours" merge strategy disguised as a [StrategyResolve] strategy, because stupid JGit will
 * always type-cast the strategy as a ResolveMerger and fail if something else is used.
 */
class FakeOneSidedStrategy2 : JgitMergeStrategy() {

  val ours = 0
  val theirs = 1

  override fun getName(): String = OURS.name

  override fun newMerger(db: Repository): Merger = OneSide(db, ours)

  override fun newMerger(db: Repository, inCore: Boolean): Merger = TODO()
  override fun newMerger(inserter: ObjectInserter, config: Config): Merger = TODO()

  internal class OneSide(local: Repository?, private val treeIndex: Int) : ResolveMerger(local) {
    @Throws(IOException::class) override fun mergeImpl(): Boolean {
      return treeIndex < sourceTrees.size
    }

    override fun getResultTreeId(): ObjectId? {
      return sourceTrees[treeIndex]
    }

    override fun getBaseCommitId(): ObjectId? {
      return null
    }
  }
}
