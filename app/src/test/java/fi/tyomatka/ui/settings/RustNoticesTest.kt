package fi.tyomatka.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ferrostarin natiivikirjaston Rust-riippuvuudet ja Rustin standardikirjasto eivät näy Gradlen riippuvuusluettelossa,
 * joten niiden merkinnät kootaan erikseen (`scripts/rust_notices.py`). Ferrostarin päivitys kaatuu tähän, kunnes
 * merkinnät on koottu uudelleen.
 */
class RustNoticesTest {
    private val text = File("src/main/res/raw/notice_ferrostar_rust.txt").readText()
    private val lines = text.lines()
    private val divider = "-".repeat(40)
    private val firstDivider = lines.indexOf(divider)
    private val header = lines.take(firstDivider)

    /** Pakettirivi: `nimi versio (lisenssi)`. */
    private val crateLine = Regex("""^([A-Za-z0-9_.-]+) (\d[0-9A-Za-z.+-]*) \((.*)\)$""")
    private val crates = header.mapNotNull { crateLine.find(it) }.associate { "${it.groupValues[1]} ${it.groupValues[2]}" to it.groupValues[3] }

    /** Lisenssitekstit paketeittain: tekstin otsikkona ovat paketit, joita se koskee. */
    private val textsByCrate: Map<String, List<String>> = run {
        val result = mutableMapOf<String, MutableList<String>>()
        var i = firstDivider
        while (i in lines.indices && i + 2 < lines.size) {
            if (lines[i] == divider && lines[i + 2] == divider) {
                val end = (i + 3 until lines.size).firstOrNull { lines[it] == divider && it + 2 < lines.size && lines[it + 2] == divider } ?: lines.size
                val body = lines.subList(i + 3, end).joinToString("\n")
                lines[i + 1].split(", ").forEach { result.getOrPut(it) { mutableListOf() } += body }
                i = end
            } else {
                i++
            }
        }
        result
    }

    @Test
    fun `merkinnät on koottu käytössä olevasta ferrostarin versiosta`() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val version = Regex("""(?m)^ferrostar = "([^"]+)"""").find(catalog)?.groupValues?.get(1)

        assertEquals("Ferrostar $version: libferrostar.so", lines.first())
    }

    @Test
    fun `jokaisella paketilla on täysi lisenssiteksti`() {
        val markers = listOf(
            "Permission is hereby granted",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            "Mozilla Public License Version 2.0",
            "Permission to use, copy, modify, and/or distribute",
            "This software is provided 'as-is'",
            "This is free and unencumbered software",
            "Redistribution and use in source and binary forms",
        )

        assertTrue(crates.size > 100, "${crates.size} pakettia")
        assertEquals(emptyList(), crates.keys.filterNot { it in textsByCrate })
        assertEquals(emptyList(), crates.keys.filter { crate -> textsByCrate.getValue(crate).none { body -> markers.any { it in body } } })
    }

    /** Totuus on cargon pakettipuu, jonka generaattori kirjoittaa kansioon `scripts/rust-crates`. */
    @Test
    fun `luettelo vastaa cargon pakettipuuta`() {
        val expected = listOf("ferrostar", "std")
            .flatMap { File("../scripts/rust-crates/$it.txt").readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toSet()

        assertTrue(expected.size > 100, "${expected.size} pakettia")
        assertEquals(expected, crates.keys)
    }

    @Test
    fun `unicode-lisenssin teksti on mukana sitä vaativilla paketeilla`() {
        val needing = crates.filter { (crate, license) -> "Unicode" in license || crate.startsWith("core ") }.keys

        assertTrue(needing.size >= 2, needing.toString())
        assertEquals(emptyList(), needing.filterNot { crate -> textsByCrate.getValue(crate).any { "UNICODE LICENSE V3" in it } })
    }

    @Test
    fun `rustin standardikirjasto on mukana`() {
        val stdStart = header.indexOf("Rust standard library:")

        assertTrue(stdStart > 0)
        val std = header.drop(stdStart + 1).takeWhile { it.isNotBlank() }.map { it.substringBefore(" ") }
        assertTrue(std.containsAll(listOf("std", "core", "alloc", "compiler_builtins")), std.toString())
        assertTrue("The Rust Standard Library is dual-licensed under Apache 2.0 and MIT terms." in text)
        assertTrue(Regex("""compiled from Rust \(rustc \d+\.\d+\.\d+""").containsMatchIn(text))
    }

    @Test
    fun `mpl-paketeille kerrotaan lähdekoodin sijainti`() {
        val mpl = crates.filterValues { "MPL" in it }.keys

        assertTrue(mpl.isNotEmpty())
        mpl.forEach { crate ->
            val (name, version) = crate.split(" ")
            assertTrue("$crate: https://crates.io/crates/$name/$version" in header, crate)
        }
    }

    @Test
    fun `lisenssisivulla on rust-kirjastojen rivi`() {
        assertEquals(listOf("rust"), NATIVE_LICENSES.map { it.key })
    }
}
