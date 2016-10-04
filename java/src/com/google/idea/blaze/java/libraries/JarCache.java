/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.java.libraries;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.idea.blaze.base.filecache.FileCache;
import com.google.idea.blaze.base.filecache.FileDiffer;
import com.google.idea.blaze.base.io.FileSizeScanner;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.prefetch.FetchExecutor;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.BlazeSyncParams;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.java.settings.BlazeJavaUserSettings;
import com.google.idea.blaze.java.sync.BlazeLibraryCollector;
import com.google.idea.blaze.java.sync.model.BlazeJarLibrary;
import com.google.idea.blaze.java.sync.model.BlazeLibrary;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Local cache of the jars referenced by the project. */
public class JarCache {
  private static final Logger LOG = Logger.getInstance(JarCache.class);
  public static final BoolExperiment ENABLE_JAR_CACHE =
      new BoolExperiment("enable.jar.cache", true);

  private final Project project;
  private final BlazeImportSettings importSettings;
  private final File cacheDir;
  private boolean enabled;
  @Nullable private BiMap<File, String> sourceFileToCacheKey = null;

  public static JarCache getInstance(Project project) {
    return ServiceManager.getService(project, JarCache.class);
  }

  public JarCache(Project project) {
    this.project = project;
    this.importSettings = BlazeImportSettingsManager.getInstance(project).getImportSettings();
    this.cacheDir = getCacheDir();
  }

  public void onSync(
      BlazeContext context, BlazeProjectData projectData, BlazeSyncParams.SyncMode syncMode) {
    Collection<BlazeLibrary> libraries = BlazeLibraryCollector.getLibraries(projectData);
    boolean fullRefresh = syncMode == BlazeSyncParams.SyncMode.FULL;
    boolean enabled = updateEnabled();

    if (!enabled || fullRefresh) {
      clearCache();
    }
    if (!enabled) {
      return;
    }

    boolean attachAllSourceJars = BlazeJavaUserSettings.getInstance().getAttachSourcesByDefault();
    SourceJarManager sourceJarManager = SourceJarManager.getInstance(project);

    List<BlazeJarLibrary> jarLibraries =
        libraries
            .stream()
            .filter(library -> library instanceof BlazeJarLibrary)
            .map(library -> (BlazeJarLibrary) library)
            .collect(Collectors.toList());

    BiMap<File, String> sourceFileToCacheKey = HashBiMap.create(jarLibraries.size());
    for (BlazeJarLibrary library : jarLibraries) {
      File jarFile = library.libraryArtifact.jarForIntellijLibrary().getFile();
      sourceFileToCacheKey.put(jarFile, cacheKeyForJar(jarFile));

      boolean attachSourceJar =
          attachAllSourceJars || sourceJarManager.hasSourceJarAttached(library.key);
      if (attachSourceJar && library.libraryArtifact.sourceJar != null) {
        File srcJarFile = library.libraryArtifact.sourceJar.getFile();
        sourceFileToCacheKey.put(srcJarFile, cacheKeyForSourceJar(srcJarFile));
      }
    }

    this.sourceFileToCacheKey = sourceFileToCacheKey;
    refresh(context, true);
  }

  public boolean isEnabled() {
    return enabled;
  }

  private boolean updateEnabled() {
    this.enabled =
        BlazeJavaUserSettings.getInstance().getUseJarCache()
            && ENABLE_JAR_CACHE.getValue()
            && !ApplicationManager.getApplication().isUnitTestMode();
    return enabled;
  }

  /** Refreshes any updated files in the cache. Does not add or removes any files */
  public void refresh() {
    refresh(null, false);
  }

  private void refresh(@Nullable BlazeContext context, boolean removeMissingFiles) {
    if (!enabled || sourceFileToCacheKey == null) {
      return;
    }

    // Ensure the cache dir exists
    if (!cacheDir.exists()) {
      if (!cacheDir.mkdirs()) {
        LOG.error("Could not create jar cache directory");
        return;
      }
    }

    // Discover state of source jars
    ImmutableMap<File, Long> sourceFileTimestamps =
        FileDiffer.readFileState(sourceFileToCacheKey.keySet());
    if (sourceFileTimestamps == null) {
      return;
    }
    ImmutableMap.Builder<String, Long> sourceFileCacheKeyToTimestamp = ImmutableMap.builder();
    for (Map.Entry<File, Long> entry : sourceFileTimestamps.entrySet()) {
      String cacheKey = sourceFileToCacheKey.get(entry.getKey());
      sourceFileCacheKeyToTimestamp.put(cacheKey, entry.getValue());
    }

    // Discover current on-disk cache state
    File[] cacheFiles = cacheDir.listFiles();
    assert cacheFiles != null;
    ImmutableMap<File, Long> cacheFileTimestamps =
        FileDiffer.readFileState(Lists.newArrayList(cacheFiles));
    if (cacheFileTimestamps == null) {
      return;
    }
    ImmutableMap.Builder<String, Long> cachedFileCacheKeyToTimestamp = ImmutableMap.builder();
    for (Map.Entry<File, Long> entry : cacheFileTimestamps.entrySet()) {
      String cacheKey = entry.getKey().getName(); // Cache key == file name
      cachedFileCacheKeyToTimestamp.put(cacheKey, entry.getValue());
    }

    List<String> updatedFiles = Lists.newArrayList();
    List<String> removedFiles = Lists.newArrayList();
    FileDiffer.diffState(
        cachedFileCacheKeyToTimestamp.build(),
        sourceFileCacheKeyToTimestamp.build(),
        updatedFiles,
        removedFiles);

    ListeningExecutorService executor = FetchExecutor.EXECUTOR;
    List<ListenableFuture<?>> futures = Lists.newArrayList();
    Map<String, File> cacheKeyToSourceFile = sourceFileToCacheKey.inverse();
    for (String cacheKey : updatedFiles) {
      File sourceFile = cacheKeyToSourceFile.get(cacheKey);
      File cacheFile = cacheFileForKey(cacheKey);
      futures.add(
          executor.submit(
              () -> {
                try {
                  Files.copy(
                      Paths.get(sourceFile.getPath()),
                      Paths.get(cacheFile.getPath()),
                      StandardCopyOption.REPLACE_EXISTING,
                      StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException e) {
                  LOG.warn(e);
                }
              }));
    }

    if (removeMissingFiles) {
      for (String cacheKey : removedFiles) {
        File cacheFile = cacheFileForKey(cacheKey);
        futures.add(
            executor.submit(
                () -> {
                  try {
                    Files.deleteIfExists(Paths.get(cacheFile.getPath()));
                  } catch (IOException e) {
                    LOG.warn(e);
                  }
                }));
      }
    }

    try {
      Futures.allAsList(futures).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn(e);
    } catch (ExecutionException e) {
      LOG.error(e);
    }
    if (context != null && updatedFiles.size() > 0) {
      context.output(PrintOutput.log(String.format("Copied %d jars", updatedFiles.size())));
    }
    if (context != null && removedFiles.size() > 0 && removeMissingFiles) {
      context.output(PrintOutput.log(String.format("Removed %d jars", removedFiles.size())));
    }
    if (context != null) {
      try {
        File[] finalCacheFiles = cacheDir.listFiles();
        assert finalCacheFiles != null;
        ImmutableMap<File, Long> cacheFileSizes =
            FileSizeScanner.readFilesizes(Lists.newArrayList(finalCacheFiles));
        Long total =
            cacheFileSizes.values().stream().reduce((size1, size2) -> size1 + size2).orElse(0L);
        context.output(
            PrintOutput.log(
                String.format(
                    "Total Jar Cache size: %d kB (%d files)",
                    total / 1024, finalCacheFiles.length)));
      } catch (Exception e) {
        LOG.warn("Could not determine cache size", e);
      }
    }
  }

  private void clearCache() {
    if (cacheDir.exists()) {
      File[] cacheFiles = cacheDir.listFiles();
      if (cacheFiles != null) {
        FileUtil.asyncDelete(Lists.newArrayList(cacheFiles));
      }
    }
    sourceFileToCacheKey = null;
  }

  /** Gets the cached file for a jar. If it doesn't exist, we return the file from the library. */
  public File getCachedJar(BlazeJarLibrary library) {
    File file = library.libraryArtifact.jarForIntellijLibrary().getFile();
    if (!enabled || sourceFileToCacheKey == null) {
      return file;
    }
    String cacheKey = sourceFileToCacheKey.get(file);
    if (cacheKey == null) {
      return file;
    }
    return cacheFileForKey(cacheKey);
  }

  /** Gets the cached file for a source jar. */
  @Nullable
  public File getCachedSourceJar(BlazeJarLibrary library) {
    if (library.libraryArtifact.sourceJar == null) {
      return null;
    }
    File file = library.libraryArtifact.sourceJar.getFile();
    if (!enabled || sourceFileToCacheKey == null) {
      return file;
    }
    String cacheKey = sourceFileToCacheKey.get(file);
    if (cacheKey == null) {
      return file;
    }
    return cacheFileForKey(cacheKey);
  }

  private static String cacheKeyInternal(File jar) {
    int parentHash = jar.getParent().hashCode();
    return FileUtil.getNameWithoutExtension(jar) + "_" + Integer.toHexString(parentHash);
  }

  private static String cacheKeyForJar(File jar) {
    return cacheKeyInternal(jar) + ".jar";
  }

  private static String cacheKeyForSourceJar(File srcjar) {
    return cacheKeyInternal(srcjar) + "-src.jar";
  }

  private File cacheFileForKey(String key) {
    return new File(cacheDir, key);
  }

  private File getCacheDir() {
    return new File(BlazeDataStorage.getProjectDataDir(importSettings), "libraries");
  }

  static class FileCacheAdapter implements FileCache {
    @Override
    public String getName() {
      return "Jar Cache";
    }

    @Override
    public void onSync(
        Project project,
        BlazeContext context,
        ProjectViewSet projectViewSet,
        BlazeProjectData projectData,
        BlazeSyncParams.SyncMode syncMode) {
      getInstance(project).onSync(context, projectData, syncMode);
    }

    @Override
    public void refreshFiles(Project project) {
      getInstance(project).refresh();
    }
  }
}
