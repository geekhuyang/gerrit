// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.restapi.change;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.groupingBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Comment.Range;
import com.google.gerrit.entities.HumanComment;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.DiffPreferencesInfo.Whitespace;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.change.CommentThread;
import com.google.gerrit.server.change.CommentThreads;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.patch.DiffMappings;
import com.google.gerrit.server.patch.GitPositionTransformer;
import com.google.gerrit.server.patch.GitPositionTransformer.BestPositionOnConflict;
import com.google.gerrit.server.patch.GitPositionTransformer.FileMapping;
import com.google.gerrit.server.patch.GitPositionTransformer.Mapping;
import com.google.gerrit.server.patch.GitPositionTransformer.Position;
import com.google.gerrit.server.patch.GitPositionTransformer.PositionedEntity;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListKey;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Container for all logic necessary to port comments to a target patchset.
 *
 * <p>A ported comment is a comment which was left on an earlier patchset and is shown on a later
 * patchset. If a comment eligible for porting (e.g. before target patchset) can't be matched to its
 * exact position in the target patchset, we'll map it to its next best location. This can also
 * include a transformation of a line comment into a file comment.
 */
@Singleton
public class CommentPorter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GitPositionTransformer positionTransformer =
      new GitPositionTransformer(BestPositionOnConflict.INSTANCE);
  private final PatchListCache patchListCache;
  private final CommentsUtil commentsUtil;

  @Inject
  public CommentPorter(PatchListCache patchListCache, CommentsUtil commentsUtil) {
    this.patchListCache = patchListCache;
    this.commentsUtil = commentsUtil;
  }

  /**
   * Ports the given comments to the target patchset.
   *
   * <p>Not all given comments are ported. Only those fulfilling some criteria (e.g. before target
   * patchset) are considered eligible for porting.
   *
   * <p>The returned comments represent the ported version. They don't bear any indication to which
   * patchset they were ported. This is intentional as the target patchset should be obvious from
   * the API or the used REST resources. The returned comments still have the patchset field filled.
   * It contains the reference to the patchset on which the comment was originally left. That
   * patchset number can vary among the returned comments as all comments before the target patchset
   * are potentially eligible for porting.
   *
   * <p>The number of returned comments can be smaller (-> only eligible ones are ported!) or larger
   * compared to the provided comments. The latter happens when files appear as copied in the target
   * patchset. In such a situation, the same comment UUID will occur more than once in the returned
   * comments.
   *
   * @param changeNotes the {@link ChangeNotes} of the change to which the comments belong
   * @param targetPatchset the patchset to which the comments should be ported
   * @param comments the original comments
   * @return the ported comments, in no particular order
   */
  public ImmutableList<HumanComment> portComments(
      ChangeNotes changeNotes, PatchSet targetPatchset, List<HumanComment> comments) {

    ImmutableList<HumanComment> relevantComments = filterToRelevant(comments, targetPatchset);
    return port(changeNotes, targetPatchset, relevantComments);
  }

  private ImmutableList<HumanComment> filterToRelevant(
      List<HumanComment> allComments, PatchSet targetPatchset) {
    ImmutableList<HumanComment> previousPatchsetsComments =
        allComments.stream()
            .filter(comment -> comment.key.patchSetId < targetPatchset.number())
            .collect(toImmutableList());

    ImmutableSet<CommentThread<HumanComment>> commentThreads =
        CommentThreads.forComments(previousPatchsetsComments).getThreads();

    return commentThreads.stream()
        .filter(CommentThread::unresolved)
        .map(CommentThread::comments)
        .flatMap(Collection::stream)
        .collect(toImmutableList());
  }

  private ImmutableList<HumanComment> port(
      ChangeNotes notes, PatchSet targetPatchset, List<HumanComment> comments) {
    Map<Integer, ImmutableList<HumanComment>> commentsPerPatchset =
        comments.stream().collect(groupingBy(comment -> comment.key.patchSetId, toImmutableList()));

    ImmutableList.Builder<HumanComment> portedComments =
        ImmutableList.builderWithExpectedSize(comments.size());
    for (Integer originalPatchsetId : commentsPerPatchset.keySet()) {
      ImmutableList<HumanComment> patchsetComments = commentsPerPatchset.get(originalPatchsetId);
      PatchSet originalPatchset =
          notes.getPatchSets().get(PatchSet.id(notes.getChangeId(), originalPatchsetId));
      if (originalPatchset != null) {
        portedComments.addAll(
            portSamePatchset(
                notes.getProjectName(),
                notes.getChange(),
                originalPatchset,
                targetPatchset,
                patchsetComments));
      } else {
        logger.atWarning().log(
            String.format(
                "Some comments which should be ported refer to the non-existent patchset %s of"
                    + " change %d. Omitting %d affected comments.",
                originalPatchsetId, notes.getChangeId().get(), patchsetComments.size()));
      }
    }
    return portedComments.build();
  }

  private ImmutableList<HumanComment> portSamePatchset(
      Project.NameKey project,
      Change change,
      PatchSet originalPatchset,
      PatchSet targetPatchset,
      ImmutableList<HumanComment> comments) {
    Map<Short, List<HumanComment>> commentsPerSide =
        comments.stream().collect(groupingBy(comment -> comment.side));
    ImmutableList.Builder<HumanComment> portedComments = ImmutableList.builder();
    for (Entry<Short, List<HumanComment>> sideAndComments : commentsPerSide.entrySet()) {
      portedComments.addAll(
          portSamePatchsetAndSide(
              project,
              change,
              originalPatchset,
              targetPatchset,
              sideAndComments.getValue(),
              sideAndComments.getKey()));
    }
    return portedComments.build();
  }

  private ImmutableList<HumanComment> portSamePatchsetAndSide(
      Project.NameKey project,
      Change change,
      PatchSet originalPatchset,
      PatchSet targetPatchset,
      List<HumanComment> comments,
      short side) {
    ImmutableSet<Mapping> mappings;
    try {
      mappings = loadMappings(project, change, originalPatchset, targetPatchset, side);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Could not determine some necessary diff mappings for porting comments on change %s from"
              + " patchset %s to patchset %s. Mapping %d affected comments to the fallback"
              + " destination.",
          change.getChangeId(),
          originalPatchset.id().getId(),
          targetPatchset.id().getId(),
          comments.size());
      mappings = getFallbackMappings(comments);
    }

    ImmutableList<PositionedEntity<HumanComment>> positionedComments =
        comments.stream().map(this::toPositionedEntity).collect(toImmutableList());
    return positionTransformer.transform(positionedComments, mappings).stream()
        .map(PositionedEntity::getEntityAtUpdatedPosition)
        .collect(toImmutableList());
  }

  private ImmutableSet<Mapping> loadMappings(
      Project.NameKey project,
      Change change,
      PatchSet originalPatchset,
      PatchSet targetPatchset,
      short side)
      throws PatchListNotAvailableException {
    ObjectId originalCommit = determineCommitId(change, originalPatchset, side);
    ObjectId targetCommit = determineCommitId(change, targetPatchset, side);
    return loadCommitMappings(project, originalCommit, targetCommit);
  }

  private ObjectId determineCommitId(Change change, PatchSet patchset, short side) {
    return commentsUtil
        .determineCommitId(change, patchset, side)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "Commit indicated by change %d, patchset %d, side %d doesn't exist.",
                        change.getId().get(), patchset.id().get(), side)));
  }

  private ImmutableSet<Mapping> loadCommitMappings(
      Project.NameKey project, ObjectId originalCommit, ObjectId targetCommit)
      throws PatchListNotAvailableException {
    PatchList patchList =
        patchListCache.get(
            PatchListKey.againstCommit(originalCommit, targetCommit, Whitespace.IGNORE_NONE),
            project);
    return patchList.getPatches().stream().map(DiffMappings::toMapping).collect(toImmutableSet());
  }

  private ImmutableSet<Mapping> getFallbackMappings(List<HumanComment> comments) {
    // Consider all files as deleted. -> Comments will be ported to the fallback destination, which
    // currently are patchset-level comments.
    return comments.stream()
        .map(comment -> comment.key.filename)
        .distinct()
        .map(FileMapping::forDeletedFile)
        .map(fileMapping -> Mapping.create(fileMapping, ImmutableSet.of()))
        .collect(toImmutableSet());
  }

  private PositionedEntity<HumanComment> toPositionedEntity(HumanComment comment) {
    return PositionedEntity.create(
        comment, CommentPorter::extractPosition, CommentPorter::createCommentAtNewPosition);
  }

  private static Position extractPosition(HumanComment comment) {
    Position.Builder positionBuilder = Position.builder();
    // Patchset-level comments don't have a file path. The transformation logic still works when
    // using the magic file path but it doesn't hurt to use the actual representation for "no file"
    // internally.
    if (!Patch.PATCHSET_LEVEL.equals(comment.key.filename)) {
      positionBuilder.filePath(comment.key.filename);
    }
    return positionBuilder.lineRange(extractLineRange(comment)).build();
  }

  private static Optional<GitPositionTransformer.Range> extractLineRange(HumanComment comment) {
    // Line specifications in comment are 1-based. Line specifications in Position are 0-based.
    if (comment.range != null) {
      // The combination of (line, charOffset) is exclusive and must be mapped to an exclusive line.
      int exclusiveEndLine =
          comment.range.endChar > 0 ? comment.range.endLine : comment.range.endLine - 1;
      return Optional.of(
          GitPositionTransformer.Range.create(comment.range.startLine - 1, exclusiveEndLine));
    }
    if (comment.lineNbr > 0) {
      return Optional.of(GitPositionTransformer.Range.create(comment.lineNbr - 1, comment.lineNbr));
    }
    // File comment -> no range.
    return Optional.empty();
  }

  private static HumanComment createCommentAtNewPosition(
      HumanComment originalComment, Position newPosition) {
    HumanComment portedComment = new HumanComment(originalComment);
    portedComment.key.filename = newPosition.filePath().orElse(Patch.PATCHSET_LEVEL);
    if (portedComment.range != null && newPosition.lineRange().isPresent()) {
      // Comment was a range comment and also stayed one.
      portedComment.range =
          toRange(
              newPosition.lineRange().get(),
              portedComment.range.startChar,
              portedComment.range.endChar);
      portedComment.lineNbr = portedComment.range.endLine;
    } else {
      portedComment.range = null;
      // No line -> use 0 = file comment or any other comment type without an explicit line.
      portedComment.lineNbr = newPosition.lineRange().map(range -> range.start() + 1).orElse(0);
    }
    if (Patch.PATCHSET_LEVEL.equals(portedComment.key.filename)) {
      // Correct the side of the comment to Side.REVISION (= 1) if the comment was changed to
      // patchset level.
      portedComment.side = 1;
    }
    return portedComment;
  }

  private static Range toRange(
      GitPositionTransformer.Range lineRange, int originalStartChar, int originalEndChar) {
    int adjustedEndLine = originalEndChar > 0 ? lineRange.end() : lineRange.end() + 1;
    return new Range(lineRange.start() + 1, originalStartChar, adjustedEndLine, originalEndChar);
  }
}
