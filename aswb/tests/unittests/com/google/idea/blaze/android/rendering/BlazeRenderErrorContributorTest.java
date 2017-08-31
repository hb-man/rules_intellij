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
package com.google.idea.blaze.android.rendering;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tools.idea.rendering.RenderErrorContributor;
import com.android.tools.idea.rendering.RenderErrorModelFactory;
import com.android.tools.idea.rendering.RenderResult;
import com.android.tools.idea.rendering.errors.ui.RenderErrorModel;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.JavaIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.ideinfo.TargetMapBuilder;
import com.google.idea.blaze.base.lang.buildfile.references.BuildReferenceManager;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.MockBlazeProjectDataBuilder;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.settings.Blaze.BuildSystem;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.settings.BlazeImportSettingsManager;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.base.targetmaps.SourceToTargetMap;
import com.google.idea.blaze.base.targetmaps.TransitiveDependencyMap;
import com.intellij.mock.MockModule;
import com.intellij.mock.MockPsiFile;
import com.intellij.mock.MockPsiManager;
import com.intellij.mock.MockVirtualFile;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.MockFileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.JavaPsiFacadeImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScopeBuilder;
import com.intellij.psi.search.ProjectScopeBuilderImpl;
import java.io.File;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BlazeRenderErrorContributor}. */
@RunWith(JUnit4.class)
public class BlazeRenderErrorContributorTest extends BlazeTestCase {
  private static final String BLAZE_BIN = "blaze-out/crosstool/bin";
  private static final String GENERATED_RESOURCES_ERROR = "Generated resources";
  private static final String NON_STANDARD_MANIFEST_NAME_ERROR = "Non-standard manifest name";
  private static final String MISSING_CLASS_DEPENDENCIES_ERROR = "Missing class dependencies";

  private static final WorkspaceRoot workspaceRoot = new WorkspaceRoot(new File("/"));
  private Module module;
  private MockBlazeProjectDataManager projectDataManager;
  private BlazeRenderErrorContributor.BlazeProvider provider;

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    super.initTest(applicationServices, projectServices);
    applicationServices.register(FileTypeManager.class, new MockFileTypeManager());

    projectServices.register(ProjectFileIndex.class, mock(ProjectFileIndex.class));
    projectServices.register(BuildReferenceManager.class, new MockBuildReferenceManager(project));
    projectServices.register(TransitiveDependencyMap.class, new TransitiveDependencyMap(project));
    projectServices.register(ProjectScopeBuilder.class, new ProjectScopeBuilderImpl(project));
    projectServices.register(
        AndroidResourceModuleRegistry.class, new AndroidResourceModuleRegistry());

    BlazeImportSettingsManager importSettingsManager = new BlazeImportSettingsManager();
    BlazeImportSettings settings = new BlazeImportSettings("", "", "", "", BuildSystem.Blaze);
    importSettingsManager.setImportSettings(settings);
    projectServices.register(BlazeImportSettingsManager.class, importSettingsManager);

    createPsiClassesAndSourceToTargetMap(projectServices);

    projectDataManager = new MockBlazeProjectDataManager();
    projectServices.register(BlazeProjectDataManager.class, projectDataManager);

    ExtensionPoint<RenderErrorContributor.Provider> extensionPoint =
        registerExtensionPoint(
            ExtensionPointName.create("com.android.rendering.renderErrorContributor"),
            RenderErrorContributor.Provider.class);
    extensionPoint.registerExtension(new RenderErrorContributor.Provider());
    extensionPoint.registerExtension(new BlazeRenderErrorContributor.BlazeProvider());

    module = new MockModule(project, () -> {});

    // For the isApplicable tests.
    provider = new BlazeRenderErrorContributor.BlazeProvider();
  }

  @Test
  public void testProviderIsApplicable() {
    assertThat(provider.isApplicable(project)).isTrue();
  }

  @Test
  public void testProviderNotApplicableIfNotBlaze() {
    BlazeImportSettingsManager.getInstance(project).loadState(null);
    assertThat(provider.isApplicable(project)).isFalse();
  }

  @Test
  public void testNoIssuesIfNoErrors() {
    PsiFile file = new MockPsiFile(new MockPsiManager(project));
    file.putUserData(ModuleUtilCore.KEY_MODULE, module);
    RenderResult result = RenderResult.createBlank(file);
    RenderErrorModel errorModel = RenderErrorModelFactory.createErrorModel(result, null);
    assertThat(errorModel.getIssues()).isEmpty();
  }

  @Test
  public void testNoBlazeIssuesIfNoRelatedErrors() {
    RenderErrorModel errorModel = createRenderErrorModelWithBrokenClasses();
    errorModel
        .getIssues()
        .forEach(
            issue ->
                assertThat(issue.getSummary())
                    .isNoneOf(
                        GENERATED_RESOURCES_ERROR,
                        NON_STANDARD_MANIFEST_NAME_ERROR,
                        MISSING_CLASS_DEPENDENCIES_ERROR));
  }

  @Test
  public void testReportGeneratedResources() {
    createTargetMapWithGeneratedResources();
    RenderErrorModel errorModel = createRenderErrorModelWithBrokenClasses();

    RenderErrorModel.Issue generatedResourcesIssue =
        Iterables.getOnlyElement(
            errorModel
                .getIssues()
                .stream()
                .filter(issue -> issue.getSummary().equals(GENERATED_RESOURCES_ERROR))
                .collect(Collectors.toList()));

    assertThat(generatedResourcesIssue.getHtmlContent())
        .isEqualTo(
            "Generated resources will not be discovered by the IDE:"
                + "<DL>"
                + "<DD>-&NBSP;"
                + "com/google/example/dependency/generated/res "
                + "from <A HREF=\"file:///src/com/google/example/dependency/BUILD\">"
                + "//com/google/example:generated</A>"
                + "<DD>-&NBSP;"
                + "com/google/example/main/generated/res "
                + "from <A HREF=\"file:///src/com/google/example/main/BUILD\">"
                + "//com/google/example:main</A>"
                + "<DD>-&NBSP;"
                + "com/google/example/transitive/generated/one/res "
                + "from <A HREF=\"file:///src/com/google/example/transitive/BUILD\">"
                + "//com/google/example/transitive:generated</A>"
                + "<DD>-&NBSP;"
                + "com/google/example/transitive/generated/two/res "
                + "from <A HREF=\"file:///src/com/google/example/transitive/BUILD\">"
                + "//com/google/example/transitive:generated</A>"
                + "</DL>"
                + "Please avoid using generated resources, then "
                + "<A HREF=\"action:sync\">sync the project</A> and "
                + "<A HREF=\"refreshRender\">refresh the layout</A>.");
  }

  @Test
  public void testReportNonStandardAndroidManifestName() {
    createTargetMapWithNonStandardAndroidManifestName();
    RenderErrorModel errorModel = createRenderErrorModelWithBrokenClasses();

    RenderErrorModel.Issue nonStandardManifestNameIssue =
        Iterables.getOnlyElement(
            errorModel
                .getIssues()
                .stream()
                .filter(issue -> issue.getSummary().equals(NON_STANDARD_MANIFEST_NAME_ERROR))
                .collect(Collectors.toList()));

    assertThat(nonStandardManifestNameIssue.getHtmlContent())
        .isEqualTo(
            "<A HREF=\"file:///src/com/google/example/main/BUILD\">"
                + "//com/google/example:main</A> "
                + "uses a non-standard name for the Android manifest: "
                + "<A HREF=\"file:///src/com/google/example/main/WeirdManifest.xml\">"
                + "WeirdManifest.xml</A>"
                + "<BR/>"
                + "Please rename it to AndroidManifest.xml, then "
                + "<A HREF=\"action:sync\">sync the project</A> and "
                + "<A HREF=\"refreshRender\">refresh the layout</A>.");
  }

  @Test
  public void testNoReportNonStandardAndroidManifestNameInDependency() {
    createTargetMapWithNonStandardAndroidManifestNameInDependency();
    RenderErrorModel errorModel = createRenderErrorModelWithBrokenClasses();

    errorModel
        .getIssues()
        .forEach(
            issue -> assertThat(issue.getSummary()).isNotEqualTo(NON_STANDARD_MANIFEST_NAME_ERROR));
  }

  @Test
  public void testReportMissingClassDependencies() {
    createTargetMapWithMissingClassDependency();
    RenderErrorModel errorModel =
        createRenderErrorModelWithMissingClasses(
            "com.google.example.independent.LibraryView",
            "com.google.example.independent.LibraryView2",
            "com.google.example.independent.Library2View",
            "com.google.example.dependent.LibraryView",
            "com.google.example.ResourceView");

    RenderErrorModel.Issue missingClassDependenciesIssue =
        Iterables.getOnlyElement(
            errorModel
                .getIssues()
                .stream()
                .filter(issue -> issue.getSummary().equals(MISSING_CLASS_DEPENDENCIES_ERROR))
                .collect(Collectors.toList()));

    assertThat(missingClassDependenciesIssue.getHtmlContent())
        .isEqualTo(
            "<A HREF=\"file:///src/com/google/example/BUILD\">"
                + "//com/google/example:resources</A> "
                + "contains resource files that reference these classes:"
                + "<DL>"
                + "<DD>-&NBSP;"
                + "<A HREF=\"openClass:com.google.example.independent.Library2View\">"
                + "com.google.example.independent.Library2View</A> "
                + "from <A HREF=\"file:///src/com/google/example/BUILD\">"
                + "//com/google/example/independent:library2</A> "
                + "<DD>-&NBSP;"
                + "<A HREF=\"openClass:com.google.example.independent.LibraryView\">"
                + "com.google.example.independent.LibraryView</A> "
                + "from <A HREF=\"file:///src/com/google/example/BUILD\">"
                + "//com/google/example/independent:library</A> "
                + "<DD>-&NBSP;"
                + "<A HREF=\"openClass:com.google.example.independent.LibraryView2\">"
                + "com.google.example.independent.LibraryView2</A> "
                + "from <A HREF=\"file:///src/com/google/example/BUILD\">"
                + "//com/google/example/independent:library</A> "
                + "</DL>"
                + "Please fix your dependencies so that "
                + "<A HREF=\"file:///src/com/google/example/BUILD\">"
                + "//com/google/example:resources</A> "
                + "correctly depends on these classes, then "
                + "<A HREF=\"action:sync\">sync the project</A> and "
                + "<A HREF=\"refreshRender\">refresh the layout</A>."
                + "<BR/>"
                + "<BR/>"
                + "<B>NOTE: blaze can still build with the incorrect dependencies "
                + "due to the way it handles resources, "
                + "but the layout editor needs them to be correct.</B>");
  }

  @Test
  public void testNoReportMissingClassDependenciesIfClassInSameTarget() {
    createTargetMapWithMissingClassDependency();
    RenderErrorModel errorModel =
        createRenderErrorModelWithMissingClasses("com.google.example.ResourceView");

    errorModel
        .getIssues()
        .forEach(
            issue -> assertThat(issue.getSummary()).isNotEqualTo(MISSING_CLASS_DEPENDENCIES_ERROR));
  }

  @Test
  public void testNoReportMissingClassDependenciesIfClassInDependency() {
    createTargetMapWithMissingClassDependency();
    RenderErrorModel errorModel =
        createRenderErrorModelWithMissingClasses("com.google.example.dependent.LibraryView");

    errorModel
        .getIssues()
        .forEach(
            issue -> assertThat(issue.getSummary()).isNotEqualTo(MISSING_CLASS_DEPENDENCIES_ERROR));
  }

  private RenderErrorModel createRenderErrorModelWithBrokenClasses() {
    PsiFile file = new MockPsiFile(new MockPsiManager(project));
    file.putUserData(ModuleUtilCore.KEY_MODULE, module);
    RenderResult result = RenderResult.createBlank(file);
    result
        .getLogger()
        .addBrokenClass("com.google.example.CustomView", new Exception("resource not found"));
    return RenderErrorModelFactory.createErrorModel(result, null);
  }

  private RenderErrorModel createRenderErrorModelWithMissingClasses(String... classNames) {
    PsiFile file = new MockPsiFile(new MockPsiManager(project));
    file.putUserData(ModuleUtilCore.KEY_MODULE, module);
    RenderResult result = RenderResult.createBlank(file);
    for (String className : classNames) {
      result.getLogger().addMissingClass(className);
    }
    return RenderErrorModelFactory.createErrorModel(result, null);
  }

  private static ArtifactLocation artifact(String relativePath, boolean isSource) {
    return ArtifactLocation.builder()
        .setIsSource(isSource)
        .setRootExecutionPathFragment(isSource ? "" : BLAZE_BIN)
        .setRelativePath(relativePath)
        .build();
  }

  private void createTargetMapWithGeneratedResources() {
    Label mainResourcesTarget = Label.create("//com/google/example:main");
    Label dependencyGeneratedResourceTarget = Label.create("//com/google/example:generated");
    Label dependencySourceResourceTarget = Label.create("//com/google/example:source");
    Label transitiveGeneratedResourcesTarget =
        Label.create("//com/google/example/transitive:generated");
    Label transitiveSourceResourceTarget = Label.create("//com/google/example/transitive:source");
    Label unrelatedGeneratedResourceTarget = Label.create("//com/google/unrelated:generated");
    Label unrelatedSourceResourceTarget = Label.create("//com/google/unrelated:source");

    ArtifactLocation mainGeneratedResource =
        artifact("com/google/example/main/generated/res", false);
    ArtifactLocation mainSourceResource = artifact("com/google/example/main/source/res", true);
    ArtifactLocation dependencyGeneratedResource =
        artifact("com/google/example/dependency/generated/res", false);
    ArtifactLocation dependencySourceResource =
        artifact("com/google/example/dependency/source/res", true);
    ArtifactLocation transitiveGeneratedResourceOne =
        artifact("com/google/example/transitive/generated/one/res", false);
    ArtifactLocation transitiveGeneratedResourceTwo =
        artifact("com/google/example/transitive/generated/two/res", false);
    ArtifactLocation transitiveSourceResource =
        artifact("com/google/example/transitive/source/res", true);
    ArtifactLocation unrelatedGeneratedResource =
        artifact("com/google/unrelated/generated/res", false);
    ArtifactLocation unrelatedSourceResource = artifact("com/google/unrelated/source/res", true);

    ArtifactLocation mainBuildFile = artifact("com/google/example/main/BUILD", true);
    ArtifactLocation dependencyBuildFile = artifact("com/google/example/dependency/BUILD", true);
    ArtifactLocation transitiveBuildFile = artifact("com/google/example/transitive/BUILD", true);
    ArtifactLocation unrelatedBuildFile = artifact("com/google/unrelated/BUILD", true);

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    registry.put(
        module,
        AndroidResourceModule.builder(TargetKey.forPlainTarget(mainResourcesTarget))
            // .addResource(mainGeneratedResource) // Dropped.
            .addResource(mainSourceResource)
            .addTransitiveResourceDependency(dependencyGeneratedResourceTarget)
            .addTransitiveResource(dependencyGeneratedResource)
            .addTransitiveResourceDependency(dependencySourceResourceTarget)
            .addTransitiveResource(dependencySourceResource)
            .addTransitiveResourceDependency(transitiveGeneratedResourcesTarget)
            .addTransitiveResource(transitiveGeneratedResourceOne)
            .addTransitiveResource(transitiveGeneratedResourceTwo)
            .addTransitiveResourceDependency(transitiveSourceResourceTarget)
            .addTransitiveResource(transitiveSourceResource)
            .build());
    // Not using these, but they should be in the registry.
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(dependencyGeneratedResourceTarget))
            // .addResource(dependencyGeneratedResource) // Dropped.
            .addTransitiveResourceDependency(transitiveSourceResourceTarget)
            .addTransitiveResource(transitiveSourceResource)
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(dependencySourceResourceTarget))
            .addResource(dependencySourceResource)
            .addTransitiveResourceDependency(transitiveGeneratedResourcesTarget)
            .addTransitiveResource(transitiveGeneratedResourceOne)
            .addTransitiveResource(transitiveGeneratedResourceTwo)
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(transitiveGeneratedResourcesTarget))
            // .addResource(transitiveGeneratedResourceOne) // Dropped.
            // .addResource(transitiveGeneratedResourceTwo) // Dropped.
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(transitiveSourceResourceTarget))
            .addResource(transitiveSourceResource)
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(unrelatedGeneratedResourceTarget))
            // .addResource(unrelatedGeneratedResource) // Dropped.
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(unrelatedSourceResourceTarget))
            .addResource(unrelatedSourceResource)
            .build());

    TargetMap targetMap =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(mainResourcesTarget)
                    .setBuildFile(mainBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(mainGeneratedResource)
                            .addResource(mainSourceResource))
                    .addDependency(dependencyGeneratedResourceTarget)
                    .addDependency(dependencySourceResourceTarget))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(dependencyGeneratedResourceTarget)
                    .setBuildFile(dependencyBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(dependencyGeneratedResource))
                    .addDependency(transitiveSourceResourceTarget))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(dependencySourceResourceTarget)
                    .setBuildFile(dependencyBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(dependencySourceResource))
                    .addDependency(transitiveGeneratedResourcesTarget))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(transitiveGeneratedResourcesTarget)
                    .setBuildFile(transitiveBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(transitiveGeneratedResourceOne)
                            .addResource(transitiveGeneratedResourceTwo)))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(transitiveSourceResourceTarget)
                    .setBuildFile(transitiveBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(transitiveSourceResource)))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(unrelatedGeneratedResourceTarget)
                    .setBuildFile(unrelatedBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(unrelatedGeneratedResource)))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(unrelatedSourceResourceTarget)
                    .setBuildFile(unrelatedBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .addResource(unrelatedSourceResource)))
            .build();

    projectDataManager.setTargetMap(targetMap);
  }

  private void createTargetMapWithNonStandardAndroidManifestName() {
    Label mainResourceTarget = Label.create("//com/google/example:main");

    ArtifactLocation mainManifest = artifact("com/google/example/main/WeirdManifest.xml", true);
    ArtifactLocation mainResource = artifact("com/google/example/main/res", true);
    ArtifactLocation mainBuildFile = artifact("com/google/example/main/BUILD", true);

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    registry.put(
        module,
        AndroidResourceModule.builder(TargetKey.forPlainTarget(mainResourceTarget))
            .addResource(mainResource)
            .build());

    TargetMap targetMap =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(mainResourceTarget)
                    .setBuildFile(mainBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .setManifestFile(mainManifest)
                            .addResource(mainResource)))
            .build();

    projectDataManager.setTargetMap(targetMap);
  }

  private void createTargetMapWithNonStandardAndroidManifestNameInDependency() {
    Label mainResourceTarget = Label.create("//com/google/example:main");
    Label dependencyResourceTarget = Label.create("//com/google/example:dependency");

    ArtifactLocation mainManifest = artifact("com/google/example/main/AndroidManifest.xml", true);
    ArtifactLocation mainResource = artifact("com/google/example/main/res", true);
    ArtifactLocation mainBuildFile = artifact("com/google/example/main/BUILD", true);

    ArtifactLocation dependencyManifest =
        artifact("com/google/example/dependency/MyManifest.xml", true);
    ArtifactLocation dependencyResource = artifact("com/google/example/dependency/res", true);
    ArtifactLocation dependencyBuildFile = artifact("com/google/example/dependency/BUILD", true);

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    registry.put(
        module,
        AndroidResourceModule.builder(TargetKey.forPlainTarget(mainResourceTarget))
            .addResource(mainResource)
            .addTransitiveResourceDependency(dependencyResourceTarget)
            .addTransitiveResource(dependencyResource)
            .build());
    registry.put(
        mock(Module.class),
        AndroidResourceModule.builder(TargetKey.forPlainTarget(dependencyResourceTarget))
            .addResource(dependencyResource)
            .build());

    TargetMap targetMap =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(mainResourceTarget)
                    .setBuildFile(mainBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .setManifestFile(mainManifest)
                            .addResource(mainResource)))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(dependencyResourceTarget)
                    .setBuildFile(dependencyBuildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .setManifestFile(dependencyManifest)
                            .addResource(dependencyResource)))
            .build();

    projectDataManager.setTargetMap(targetMap);
  }

  private void createTargetMapWithMissingClassDependency() {
    Label parentTarget = Label.create("//com/google/example:app");
    Label independentLibraryTarget = Label.create("//com/google/example/independent:library");
    Label independentLibrary2Target = Label.create("//com/google/example/independent:library2");
    Label dependentLibraryTarget = Label.create("//com/google/example/dependent:library");
    Label resourcesTarget = Label.create("//com/google/example:resources");

    ArtifactLocation manifest = artifact("com/google/example/AndroidManifest.xml", true);
    ArtifactLocation resources = artifact("com/google/example/res", true);
    ArtifactLocation buildFile = artifact("com/google/example/BUILD", true);

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    registry.put(
        module,
        AndroidResourceModule.builder(TargetKey.forPlainTarget(resourcesTarget))
            .addResource(resources)
            .build());

    TargetMap targetMap =
        TargetMapBuilder.builder()
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(parentTarget)
                    .setBuildFile(buildFile)
                    .addDependency(independentLibraryTarget)
                    .addDependency(resourcesTarget))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(independentLibraryTarget)
                    .setBuildFile(buildFile)
                    .setJavaInfo(JavaIdeInfo.builder())
                    .addDependency(independentLibrary2Target))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(independentLibrary2Target)
                    .setBuildFile(buildFile)
                    .setJavaInfo(JavaIdeInfo.builder()))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(resourcesTarget)
                    .setBuildFile(buildFile)
                    .setAndroidInfo(
                        AndroidIdeInfo.builder()
                            .setGenerateResourceClass(true)
                            .setManifestFile(manifest)
                            .addResource(resources))
                    .addDependency(dependentLibraryTarget))
            .addTarget(
                TargetIdeInfo.builder()
                    .setLabel(dependentLibraryTarget)
                    .setBuildFile(buildFile)
                    .setJavaInfo(JavaIdeInfo.builder()))
            .build();

    projectDataManager.setTargetMap(targetMap);
  }

  private void createPsiClassesAndSourceToTargetMap(Container projectServices) {
    PsiManager psiManager = new MockPsiManager(project);

    VirtualFile independentLibraryView =
        new MockVirtualFile("src/com/google/example/independent/LibraryView.java");
    VirtualFile independentLibraryView2 =
        new MockVirtualFile("src/com/google/example/independent/LibraryView2.java");
    VirtualFile independentLibrary2View =
        new MockVirtualFile("src/com/google/example/independent/Library2View.java");
    VirtualFile dependentLibraryView =
        new MockVirtualFile("src/com/google/example/dependent/LibraryView.java");
    VirtualFile resourceView = new MockVirtualFile("src/com/google/example/ResourceView.java");

    ImmutableMap<String, PsiClass> classes =
        ImmutableMap.of(
            "com.google.example.independent.LibraryView",
            mockPsiClass(independentLibraryView),
            "com.google.example.independent.LibraryView2",
            mockPsiClass(independentLibraryView2),
            "com.google.example.independent.Library2View",
            mockPsiClass(independentLibrary2View),
            "com.google.example.dependent.LibraryView",
            mockPsiClass(dependentLibraryView),
            "com.google.example.ResourceView",
            mockPsiClass(resourceView));

    ImmutableMap<File, TargetKey> sourceToTarget =
        ImmutableMap.of(
            VfsUtilCore.virtualToIoFile(independentLibraryView),
            TargetKey.forPlainTarget(Label.create("//com/google/example/independent:library")),
            VfsUtilCore.virtualToIoFile(independentLibraryView2),
            TargetKey.forPlainTarget(Label.create("//com/google/example/independent:library")),
            VfsUtilCore.virtualToIoFile(independentLibrary2View),
            TargetKey.forPlainTarget(Label.create("//com/google/example/independent:library2")),
            VfsUtilCore.virtualToIoFile(dependentLibraryView),
            TargetKey.forPlainTarget(Label.create("//com/google/example/dependent:library")),
            VfsUtilCore.virtualToIoFile(resourceView),
            TargetKey.forPlainTarget(Label.create("//com/google/example:resources")));

    projectServices.register(
        JavaPsiFacade.class, new MockJavaPsiFacade(project, psiManager, classes));
    projectServices.register(SourceToTargetMap.class, new MockSourceToTargetMap(sourceToTarget));
  }

  private static PsiClass mockPsiClass(VirtualFile virtualFile) {
    PsiFile psiFile = mock(PsiFile.class);
    when(psiFile.getVirtualFile()).thenReturn(virtualFile);
    PsiClass psiClass = mock(PsiClass.class);
    when(psiClass.getContainingFile()).thenReturn(psiFile);
    return psiClass;
  }

  private static class MockBlazeProjectDataManager implements BlazeProjectDataManager {
    private BlazeProjectData blazeProjectData;

    public void setTargetMap(TargetMap targetMap) {
      ArtifactLocationDecoder decoder =
          (location) -> new File("/src", location.getExecutionRootRelativePath());
      this.blazeProjectData =
          MockBlazeProjectDataBuilder.builder(workspaceRoot)
              .setTargetMap(targetMap)
              .setArtifactLocationDecoder(decoder)
              .build();
    }

    @Nullable
    @Override
    public BlazeProjectData getBlazeProjectData() {
      return blazeProjectData;
    }
  }

  private static class MockBuildReferenceManager extends BuildReferenceManager {
    public MockBuildReferenceManager(Project project) {
      super(project);
    }

    @Nullable
    @Override
    public PsiElement resolveLabel(Label label) {
      return null;
    }
  }

  private static class MockJavaPsiFacade extends JavaPsiFacadeImpl {
    private ImmutableMap<String, PsiClass> classes;

    public MockJavaPsiFacade(
        Project project, PsiManager psiManager, ImmutableMap<String, PsiClass> classes) {
      super(project, psiManager, null, null);
      this.classes = classes;
    }

    @Override
    public PsiClass findClass(String qualifiedName, GlobalSearchScope scope) {
      return classes.get(qualifiedName);
    }
  }

  private static class MockSourceToTargetMap implements SourceToTargetMap {
    private ImmutableMap<File, TargetKey> sourceToTarget;

    public MockSourceToTargetMap(ImmutableMap<File, TargetKey> sourceToTarget) {
      this.sourceToTarget = sourceToTarget;
    }

    @Override
    public ImmutableCollection<Label> getTargetsToBuildForSourceFile(File file) {
      return ImmutableList.of();
    }

    @Override
    public ImmutableCollection<TargetKey> getRulesForSourceFile(File file) {
      return ImmutableList.of(sourceToTarget.get(file));
    }
  }
}
