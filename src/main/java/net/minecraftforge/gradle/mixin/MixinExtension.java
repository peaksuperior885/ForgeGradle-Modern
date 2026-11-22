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

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.tasks.SourceSet;

/**
 * DSL object registered on the project as {@code mixin}. Deliberately tiny: its only job is to
 * remember which source sets want a Mixin refmap generated, and what that refmap should be named.
 *
 * <pre>
 * mixin {
 *     add sourceSets.main, "examplemod.refmap.json"
 * }
 * </pre>
 *
 * All the actual wiring (annotation processor args, resource inclusion, and the post-reobf
 * refmap remap) happens in {@link MixinIntegration}, once the project has finished evaluating.
 */
public class MixinExtension
{
    public static class Entry
    {
        public final SourceSet sourceSet;
        public final String refMapName;

        public Entry(SourceSet sourceSet, String refMapName)
        {
            this.sourceSet = sourceSet;
            this.refMapName = refMapName;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    /**
     * Registers a source set as producing a Mixin refmap with the given file name.
     * Mirrors MixinGradle's {@code add(sourceSet, refMapName)} call.
     */
    public void add(SourceSet sourceSet, String refMapName)
    {
        entries.add(new Entry(sourceSet, refMapName));
    }

    public List<Entry> getEntries()
    {
        return entries;
    }
}