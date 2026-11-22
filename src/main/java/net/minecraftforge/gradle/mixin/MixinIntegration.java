/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.mixin;

import java.io.File;
import java.util.List;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;

import net.minecraftforge.gradle.user.TaskSingleReobf;
import net.minecraftforge.gradle.user.UserConstants;

/**
 * Optional Mixin integration for ForgeGradle.
 *
 * This is a small, from-scratch integration that handles Mixin refmap
 * generation and remapping without depending on MixinGradle.
 *
 * The integration is activated only when at least one source set is
 * registered through the mixin extension.
 *
 * Mixin dependencies and manifest attributes remain the responsibility
 * of the mod developer.
 */
public final class MixinIntegration
{
    private MixinIntegration()
    {
    }

    public static void configure(final Project project, final MixinExtension ext)
    {
        project.afterEvaluate(new Action<Project>()
        {
            @Override
            public void execute(Project project)
            {
                if (ext.getEntries().isEmpty())
                    return;

                configureMixin(project, ext);
            }
        });
    }

    private static void configureMixin(Project project, MixinExtension ext)
    {
        if (project.getTasks().findByName(UserConstants.TASK_REOBF) == null)
        {
            throw new GradleException(
                    "Mixin integration requires ForgeGradle's '" + UserConstants.TASK_REOBF
                            + "' task.");
        }

        final TaskSingleReobf reobf =
                (TaskSingleReobf) project.getTasks().getByName(UserConstants.TASK_REOBF);

        TaskRemapRefMap remapTask =
                project.getTasks().create("remapMixinRefMaps", TaskRemapRefMap.class);

        remapTask.setReobf(reobf);
        remapTask.dependsOn(reobf);

        for (MixinExtension.Entry entry : ext.getEntries())
        {
            wireSourceSet(project, reobf, entry, remapTask);
        }

        project.getTasks().getByName("assemble").dependsOn(remapTask);
    }

    private static void wireSourceSet(
            final Project project,
            TaskSingleReobf reobf,
            MixinExtension.Entry entry,
            TaskRemapRefMap remapTask)
    {
        final SourceSet sourceSet = entry.sourceSet;

        // The generated SRG stays in a private AP directory.
        // The refmap is written into a resource directory packaged in the JAR.
        File apScratchDir = project.file(
                "build/mixin/" + sourceSet.getName() + "/ap");

        final File resourceDir = project.file(
                "build/mixin/" + sourceSet.getName() + "/resources");

        final File outSrgFile = new File(apScratchDir, "mixin-ap.srg");
        final File outRefMapFile = new File(resourceDir, entry.refMapName);

        JavaCompile compileTask = (JavaCompile) project.getTasks().getByName(
                sourceSet.getCompileJavaTaskName());

        List<String> args = compileTask.getOptions().getCompilerArgs();

        args.add("-AreobfSrgFile=" + reobf.getPrimarySrg().getAbsolutePath());
        args.add("-AoutSrgFile=" + outSrgFile.getAbsolutePath());
        args.add("-AoutRefMapFile=" + outRefMapFile.getAbsolutePath());

        compileTask.doFirst(new Action<Task>()
        {
            @Override
            public void execute(Task task)
            {
                outSrgFile.getParentFile().mkdirs();
                resourceDir.mkdirs();
            }
        });

        // Only the refmap resource directory gets packaged.
        sourceSet.getResources().srcDir(resourceDir);

        Task processResources = project.getTasks().getByName(
                sourceSet.getProcessResourcesTaskName());

        processResources.dependsOn(compileTask);

        // Include the annotation processor's generated SRG in reobfuscation.
        reobf.addSecondarySrgFile(outSrgFile);

        remapTask.addRefMap(entry.refMapName);
        remapTask.dependsOn(compileTask);
    }
}
