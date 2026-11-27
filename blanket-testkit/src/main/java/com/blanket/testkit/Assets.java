// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Asset objects to fetch for an asset index: everything except sounds ({@code .ogg}), once per hash. */
public final class Assets {
    static final String RESOURCES = "https://resources.download.minecraft.net/";

    private Assets() {
    }

    public static List<Fetcher.Download> objects(JsonObject index, Path assetsDir) {
        Map<String, Fetcher.Download> byHash = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : index.getAsJsonObject("objects").entrySet()) {
            if (e.getKey().endsWith(".ogg")) {
                continue;
            }
            String h = e.getValue().getAsJsonObject().get("hash").getAsString();
            byHash.putIfAbsent(h, new Fetcher.Download(RESOURCES + h.substring(0, 2) + "/" + h,
                    assetsDir.resolve("objects").resolve(h.substring(0, 2)).resolve(h)));
        }
        return new ArrayList<>(byHash.values());
    }
}
