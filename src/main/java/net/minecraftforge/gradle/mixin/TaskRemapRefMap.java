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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.md_5.specialsource.JarMapping;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.user.TaskSingleReobf;

/**
 * Rewrites the "resolved target" side of a Mixin refmap's entries after reobfJar has already run,
 * using the exact SRG data reobfJar itself loaded (primary SRG + any secondary SRG files + extra
 * SRG lines). Operates in place on reobfJar's output jar.
 *
 * Refmap shape (the part this task cares about):
 * <pre>
 * {
 *   "mappings": {
 *     "com.example.mixin.MixinFoo": {
 *       "field_70165_t:D": "field_70165_t:D"   // key = stable searge selector written by the AP
 *     }                                         // value = resolved target name -> this gets remapped
 *   }
 * }
 * </pre>
 *
 * SpecialSource's {@link JarMapping} keys its {@code fields}/{@code methods} maps by
 * "owner/name" (fields) and "owner/name descriptor" (methods) - there's no separate "MethodData"
 * type in this version. Most refmap entries don't carry an owner prefix at all, so this task
 * additionally builds a bare-name reverse index and relies on searge names being globally unique
 * (that's the entire point of searge naming) to resolve those unambiguously. If a bare name
 * happens to map to more than one distinct target (only possible from custom extra SRG lines,
 * not from normal MCP/searge data), it's left unmapped rather than guessed at.
 */
public class TaskRemapRefMap extends DefaultTask
{
    private static final String AMBIGUOUS = "\u0000ambiguous";

    private TaskSingleReobf reobf;
    private final List<String> refMapNames = new ArrayList<String>();

    public TaskRemapRefMap()
    {
        // always rerun - this is cheap and correctness depends on reobfJar's freshly written jar
        getOutputs().upToDateWhen(Constants.CALL_FALSE);
    }

    public void addRefMap(String refMapName)
    {
        refMapNames.add(refMapName);
    }

    @Internal
    public TaskSingleReobf getReobf()
    {
        return reobf;
    }

    public void setReobf(TaskSingleReobf reobf)
    {
        this.reobf = reobf;
    }

    @TaskAction
    public void remap() throws IOException
    {
        if (refMapNames.isEmpty())
            return;

        JarMapping mapping = buildMapping();
        Map<String, String> fieldByName = bareNameFieldIndex(mapping);
        Map<String, String> methodByName = bareNameMethodIndex(mapping);

        File jarFile = reobf.getJar();
        File tempOut = File.createTempFile("remapped-refmap", ".jar", getTemporaryDir());

        ZipFile in = new ZipFile(jarFile);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tempOut));

        try
        {
            java.util.Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);

                if (!entry.isDirectory() && refMapNames.contains(entry.getName()))
                {
                    byte[] remapped = remapRefMapJson(in.getInputStream(entry), mapping, fieldByName, methodByName);
                    out.write(remapped);
                }
                else
                {
                    InputStream entryIn = in.getInputStream(entry);
                    try
                    {
                        ByteStreams.copy(entryIn, out);
                    }
                    finally
                    {
                        entryIn.close();
                    }
                }

                out.closeEntry();
            }
        }
        finally
        {
            out.close();
            in.close();
        }

        Constants.copyFile(tempOut, jarFile);
        tempOut.delete();
    }

    /**
     * Loads the same SRG sources reobfJar used, the same way TaskSingleReobf does internally.
     */
    private JarMapping buildMapping() throws IOException
    {
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(reobf.getPrimarySrg());

        if (!reobf.getExtraSrgLines().isEmpty())
        {
            File extra = File.createTempFile("remap-refmap-extra", ".srg", getTemporaryDir());
            BufferedWriter writer = Files.newWriter(extra, StandardCharsets.UTF_8);
            try
            {
                for (String line : reobf.getExtraSrgLines())
                {
                    writer.write(line);
                    writer.newLine();
                }
            }
            finally
            {
                writer.close();
            }
            mapping.loadMappings(extra);
        }

        for (File f : reobf.getSecondarySrgFiles())
        {
            mapping.loadMappings(f);
        }

        return mapping;
    }

    /**
     * mapping.fields is keyed "owner/fieldName" -> newFieldName. Build a bare-name index since
     * most refmap values don't carry an owner prefix. Searge field names are globally unique, so
     * collisions are only possible from custom extra SRG lines.
     */
    private Map<String, String> bareNameFieldIndex(JarMapping mapping)
    {
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<String, String> e : mapping.fields.entrySet())
        {
            String ownerAndName = e.getKey();
            int slash = ownerAndName.lastIndexOf('/');
            String name = slash >= 0 ? ownerAndName.substring(slash + 1) : ownerAndName;
            mergeIndex(out, name, e.getValue());
        }
        return out;
    }

    /**
     * mapping.methods is keyed "owner/methodName methodDescriptor" -> newMethodName. Same idea as
     * {@link #bareNameFieldIndex}, just splitting off the descriptor first.
     */
    private Map<String, String> bareNameMethodIndex(JarMapping mapping)
    {
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<String, String> e : mapping.methods.entrySet())
        {
            String key = e.getKey();
            int space = key.indexOf(' ');
            String ownerAndName = space >= 0 ? key.substring(0, space) : key;
            int slash = ownerAndName.lastIndexOf('/');
            String name = slash >= 0 ? ownerAndName.substring(slash + 1) : ownerAndName;
            mergeIndex(out, name, e.getValue());
        }
        return out;
    }

    private void mergeIndex(Map<String, String> index, String name, String newName)
    {
        String existing = index.get(name);
        if (existing == null)
        {
            index.put(name, newName);
        }
        else if (!existing.equals(newName))
        {
            index.put(name, AMBIGUOUS);
        }
    }

    private byte[] remapRefMapJson(InputStream in, JarMapping mapping, Map<String, String> fieldByName, Map<String, String> methodByName)
            throws IOException
    {
        JsonObject root;
        InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        try
        {
            root = new JsonParser().parse(reader).getAsJsonObject();
        }
        finally
        {
            reader.close();
        }

        JsonElement mappingsEl = root.get("mappings");
        if (mappingsEl != null && mappingsEl.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> mixinClass : mappingsEl.getAsJsonObject().entrySet())
            {
                if (!mixinClass.getValue().isJsonObject())
                    continue;

                JsonObject selectors = mixinClass.getValue().getAsJsonObject();

                // snapshot first - mutating a JsonObject while iterating its own entrySet is unsafe
                List<Map.Entry<String, JsonElement>> snapshot = new ArrayList<Map.Entry<String, JsonElement>>(selectors.entrySet());

                for (Map.Entry<String, JsonElement> selectorEntry : snapshot)
                {
                    if (!selectorEntry.getValue().isJsonPrimitive())
                        continue;

                    String remapped = remapSelector(selectorEntry.getValue().getAsString(), mapping, fieldByName, methodByName);
                    selectors.addProperty(selectorEntry.getKey(), remapped);
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Gson gson = new GsonBuilder().create();
        bytes.write(gson.toJson(root).getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    /**
     * Remaps just the member name portion of a Mixin selector string, e.g.
     * "field_70165_t:D" -&gt; "posX:D", "Lowner/Class;func_70071_h_()V" -&gt; "Lowner/Class;tick()V".
     * Tries an owner-qualified lookup first (when the selector carries an "Lowner;" prefix), then
     * falls back to the bare-name index. Leaves the selector untouched if nothing matches.
     */
    private String remapSelector(String selector, JarMapping mapping, Map<String, String> fieldByName, Map<String, String> methodByName)
    {
        String owner = null;
        String member = selector;

        int ownerEnd = selector.indexOf(';');
        if (selector.startsWith("L") && ownerEnd >= 0)
        {
            owner = selector.substring(1, ownerEnd); // strip leading 'L' and trailing ';'
            member = selector.substring(ownerEnd + 1);
        }

        int parenIdx = member.indexOf('(');
        if (parenIdx >= 0)
        {
            // method selector: name(desc)ret
            String name = member.substring(0, parenIdx);
            String desc = member.substring(parenIdx);

            if (owner != null)
            {
                String mapped = mapping.methods.get(owner + "/" + name + " " + desc);
                if (mapped != null)
                    return rebuild(owner, mapped, desc);
            }

            String mapped = methodByName.get(name);
            if (mapped != null && !AMBIGUOUS.equals(mapped))
                return rebuild(owner, mapped, desc);

            return selector;
        }

        // field selector: name, or name:desc
        int colonIdx = member.indexOf(':');
        String name = colonIdx >= 0 ? member.substring(0, colonIdx) : member;
        String rest = colonIdx >= 0 ? member.substring(colonIdx) : "";

        if (owner != null)
        {
            String mapped = mapping.fields.get(owner + "/" + name);
            if (mapped != null)
                return rebuild(owner, mapped, rest);
        }

        String mapped = fieldByName.get(name);
        if (mapped != null && !AMBIGUOUS.equals(mapped))
            return rebuild(owner, mapped, rest);

        return selector;
    }

    private String rebuild(String owner, String newName, String tail)
    {
        if (owner != null)
            return "L" + owner + ";" + newName + tail;

        return newName + tail;
    }
}