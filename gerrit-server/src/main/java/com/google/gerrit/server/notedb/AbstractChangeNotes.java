// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.server.notedb;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

/** View of contents at a single ref related to some change. **/
public abstract class AbstractChangeNotes<T> {
  protected final GitRepositoryManager repoManager;
  protected final NotesMigration migration;
  private final Change.Id changeId;

  private ObjectId revision;
  private boolean loaded;

  AbstractChangeNotes(GitRepositoryManager repoManager,
      NotesMigration migration, Change.Id changeId) {
    this.repoManager = repoManager;
    this.migration = migration;
    this.changeId = changeId;
  }

  public Change.Id getChangeId() {
    return changeId;
  }

  /** @return revision of the metadata that was loaded. */
  public ObjectId getRevision() {
    return revision;
  }

  public T load() throws OrmException {
    if (loaded) {
      return self();
    }
    if (!migration.enabled() || changeId == null) {
      loadDefaults();
      return self();
    }
    try (Repository repo = repoManager.openMetadataRepository(getProjectName());
        RevWalk walk = new RevWalk(repo)) {
      Ref ref = repo.getRefDatabase().exactRef(getRefName());
      ObjectId id = ref != null ? ref.getObjectId() : null;
      revision = id != null ? walk.parseCommit(id).copy() : null;
      onLoad(walk);
      loaded = true;
    } catch (ConfigInvalidException | IOException e) {
      throw new OrmException(e);
    }
    return self();
  }

  public T reload() throws OrmException {
    loaded = false;
    return load();
  }

  public ObjectId loadRevision() throws OrmException {
    if (loaded) {
      return getRevision();
    } else if (!migration.enabled()) {
      return null;
    }
    try (Repository repo = repoManager.openMetadataRepository(getProjectName())) {
      Ref ref = repo.getRefDatabase().exactRef(getRefName());
      return ref != null ? ref.getObjectId() : null;
    } catch (IOException e) {
      throw new OrmException(e);
    }
  }

  /** Load default values for any instance variables when NoteDb is disabled. */
  protected abstract void loadDefaults();

  /**
   * @return the NameKey for the project where the notes should be stored,
   *    which is not necessarily the same as the change's project.
   */
  public abstract Project.NameKey getProjectName();

  /** @return name of the reference storing this configuration. */
  protected abstract String getRefName();

  /** Set up the metadata, parsing any state from the loaded revision. */
  protected abstract void onLoad(RevWalk walk)
      throws IOException, ConfigInvalidException;

  @SuppressWarnings("unchecked")
  protected final T self() {
    return (T) this;
  }
}
