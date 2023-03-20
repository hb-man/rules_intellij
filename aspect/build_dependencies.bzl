"""Aspects to build and collect project dependencies."""

def _package_dependencies_impl(target, ctx):
    file_name = target.label.name + ".target-info.txt"
    artifact_info_file = ctx.actions.declare_file(file_name)
    ctx.actions.write(
        artifact_info_file,
        _encode_target_info_proto(target),
    )

    return [OutputGroupInfo(
        qsync_jars = target[DependenciesInfo].compile_time_jars.to_list(),
        artifact_info_file = [artifact_info_file],
        qsync_aars = target[DependenciesInfo].aars.to_list(),
        qsync_gensrcs = target[DependenciesInfo].gensrcs.to_list(),
    )]

DependenciesInfo = provider(
    "The out-of-project dependencies",
    fields = {
        "compile_time_jars": "a list of jars generated by targets",
        "target_to_artifacts": "a map between a target and all its artifacts",
        "aars": "a list of aars with resource files",
        "gensrcs": "a list of sources generated by project targets",
    },
)

def _encode_target_info_proto(target):
    contents = []
    for label, artifacts_paths in target[DependenciesInfo].target_to_artifacts.items():
        contents.append(
            struct(target = label, artifact_paths = artifacts_paths),
        )
    return proto.encode_text(struct(artifacts = contents))

package_dependencies = aspect(
    implementation = _package_dependencies_impl,
    required_aspect_providers = [[DependenciesInfo]],
)

def generates_idl_jar(target):
    if AndroidIdeInfo not in target:
        return False
    return target[AndroidIdeInfo].idl_class_jar != None

def declares_android_resources(target, ctx):
    """
    Returns true if the target has resource files and an android provider.

    The IDE needs aars from targets that declare resources. AndroidIdeInfo
    has a defined_android_resources flag, but this returns true for additional
    cases (aidl files, etc), so we check if the target has resource files.

    Args:
      target: the target.
      ctx: the context.
    Returns:
      True if the target has resource files and an android provider.
    """
    if AndroidIdeInfo not in target:
        return False
    return hasattr(ctx.rule.attr, "resource_files") and len(ctx.rule.attr.resource_files) > 0

def _collect_dependencies_impl(target, ctx):
    return _collect_dependencies_core_impl(
        target,
        ctx,
        ctx.attr.include,
        ctx.attr.exclude,
        ctx.attr.always_build_rules,
        ctx.attr.generate_aidl_classes,
    )

def _collect_all_dependencies_for_tests_impl(target, ctx):
    return _collect_dependencies_core_impl(
        target,
        ctx,
        include = None,
        exclude = None,
        always_build_rules = None,
        generate_aidl_classes = None,
    )

def _collect_dependencies_core_impl(
        target,
        ctx,
        include,
        exclude,
        always_build_rules,
        generate_aidl_classes):
    if JavaInfo not in target:
        return DependenciesInfo(compile_time_jars = depset(), target_to_artifacts = {}, aars = depset(), gensrcs = depset())
    label = str(target.label)
    included = False
    if not include:
        # include can only be empty only when used from collect_all_dependencies_for_tests
        # aspect, which is meant to be used in tests only.
        included = False
    else:
        for inc in include.split(","):
            if label.startswith(inc):
                if label[len(inc)] in [":", "/"]:
                    included = True
                    break
    if included and len(exclude) > 0:
        for exc in exclude.split(","):
            if label.startswith(exc):
                if label[len(exc)] in [":", "/"]:
                    included = False
                    break

    if included and ctx.rule.kind in always_build_rules.split(","):
        included = False

    deps = []
    if hasattr(ctx.rule.attr, "deps"):
        deps += ctx.rule.attr.deps
    if hasattr(ctx.rule.attr, "exports"):
        deps += ctx.rule.attr.exports
    if hasattr(ctx.rule.attr, "_junit"):
        deps.append(ctx.rule.attr._junit)

    info_deps = [dep[DependenciesInfo] for dep in deps if DependenciesInfo in dep]

    trs = []
    target_to_artifacts = {}
    aar_files = []
    aar_trs = []
    gensrc_files = []
    gensrc_trs = []
    if not included:
        if info_deps:
            trs = [target[JavaInfo].compile_jars]
            target_to_artifacts = {
                label: [_output_relative_path(f.path) for f in target[JavaInfo].compile_jars.to_list()],
            }
        else:
            # For JavaInfo libraries which we don't follow any dependencies
            # we attribute all the transitive jars to them. This includes
            # all the proto variants.
            trs = [target[JavaInfo].transitive_compile_time_jars]
            target_to_artifacts = {
                label: [_output_relative_path(f.path) for f in target[JavaInfo].transitive_compile_time_jars.to_list()],
            }
        if declares_android_resources(target, ctx):
            aar_files.append(target[AndroidIdeInfo].aar)
            target_to_artifacts[label].append(_output_relative_path(target[AndroidIdeInfo].aar.path))

    else:
        if generate_aidl_classes and generates_idl_jar(target):
            target_to_artifacts[label] = []
            idl_jar = target[AndroidIdeInfo].idl_class_jar
            trs.append(depset([idl_jar]))
            target_to_artifacts[label].append(_output_relative_path(idl_jar.path))

        # Add generated java_outputs (e.g. from annotation processing
        generated_class_jars = []
        for java_output in target[JavaInfo].java_outputs:
            if java_output.generated_class_jar:
                generated_class_jars.append(java_output.generated_class_jar)
        if generated_class_jars:
            if label not in target_to_artifacts:
                target_to_artifacts[label] = []
            trs.append(depset(generated_class_jars))
            for jar in generated_class_jars:
                target_to_artifacts[label].append(_output_relative_path(jar.path))

        # Add generated sources for included targets
        if hasattr(ctx.rule.attr, "srcs"):
            for src in ctx.rule.attr.srcs:
                for file in src.files.to_list():
                    if not file.is_source:
                        gensrc_files.append(file)

            if len(gensrc_files) > 0:
                if label not in target_to_artifacts:
                    target_to_artifacts[label] = []
                for file in gensrc_files:
                    target_to_artifacts[label].append(_output_relative_path(file.path))

    for info in info_deps:
        trs.append(info.compile_time_jars)
        target_to_artifacts.update(info.target_to_artifacts)
        aar_trs.append(info.aars)
        gensrc_trs.append(info.gensrcs)

    cj = depset([], transitive = trs)
    aars = depset(aar_files, transitive = aar_trs)
    gensrcs = depset(gensrc_files, transitive = gensrc_trs)
    return [
        DependenciesInfo(
            compile_time_jars = cj,
            target_to_artifacts = target_to_artifacts,
            aars = aars,
            gensrcs = gensrcs,
        ),
    ]

def _output_relative_path(path):
    """Get file path relative to the output path.

    Args:
         path: path of artifact path = (../repo_name)? + (root_fragment)? + relative_path

    Returns:
         path relative to the output path
    """
    if (path.startswith("blaze-out/")) or (path.startswith("bazel-out/")):
        # len("blaze-out/") or len("bazel-out/")
        path = path[10:]
    return path

collect_dependencies = aspect(
    implementation = _collect_dependencies_impl,
    provides = [DependenciesInfo],
    attr_aspects = ["deps", "exports", "_junit"],
    attrs = {
        "include": attr.string(
            doc = "Comma separated list of workspace paths included in the project as source. Any targets inside here will not be built.",
            mandatory = True,
        ),
        "exclude": attr.string(
            doc = "Comma separated list of exclusions to 'include'.",
            default = "",
        ),
        "always_build_rules": attr.string(
            doc = "Comma separated list of rules. Any targets belonging to these rules will be built, regardless of location",
            default = "",
        ),
        "generate_aidl_classes": attr.bool(
            doc = "If True, generates classes for aidl files included as source for the project targets",
            default = False,
        ),
    },
)

collect_all_dependencies_for_tests = aspect(
    doc = """
    A variant of collect_dependencies aspect used by query sync integration
    tests.

    The difference is that collect_all_dependencies does not apply
    include/exclude directory filtering, which is applied in the test framework
    instead. See: test_project.bzl for more details.
    """,
    implementation = _collect_all_dependencies_for_tests_impl,
    provides = [DependenciesInfo],
    attr_aspects = ["deps", "exports", "_junit"],
)
