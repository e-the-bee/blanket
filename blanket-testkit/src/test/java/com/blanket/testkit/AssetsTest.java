// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetsTest {
    @Test
    void noSoundsAndOneDownloadPerHash() {
        // five entries of the real 1.8 index: two names share a hash, two are sounds
        List<Fetcher.Download> d = Assets.objects(Fixtures.json("assets-1.8.json"), Path.of("assets"));
        assertEquals(2, d.size(), d.toString());
        assertEquals("https://resources.download.minecraft.net/bd/bdf48ef6b5d0d23bbb02e17d04865216179f510a",
                d.get(0).url());
        assertEquals(Path.of("assets", "objects", "bd", "bdf48ef6b5d0d23bbb02e17d04865216179f510a"), d.get(0).path());
        assertEquals("https://resources.download.minecraft.net/92/92750c5f93c312ba9ab413d546f32190c56d6f1f",
                d.get(1).url());
    }
}
