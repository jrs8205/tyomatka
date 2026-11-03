"""Ferrostarin natiivikirjaston (libferrostar.so) Rust-riippuvuuksien lisenssimerkinnät sovelluksen lisenssisivulle.

Ferrostarin Android-kirjasto sisältää Rustilla käännetyn ytimen, jonka riippuvuudet eivät näy Gradlen
riippuvuusluettelossa. Merkinnät kootaan Ferrostarin julkaisun Cargo.lockista ja sen Rust-version
standardikirjastosta työkansiossa (Rust-versio: `strings libferrostar.so | grep "rustc version"`):

    gh api repos/stadiamaps/ferrostar/tarball/<versio> > ferrostar.tgz
    mkdir src && tar xzf ferrostar.tgz -C src --strip-components=1
    docker run --rm -v "$PWD:/w" -e CARGO_HOME=/w/cargo -e RUSTUP_TOOLCHAIN=<rust> rust:<rust>-slim-bookworm sh -c '
        rustup component add rust-src && rustc --version > /w/rustc-version.txt
        SYS=$(rustc --print sysroot) && mkdir -p /w/std-docs /w/rustsrc-lic && cp -r $SYS/share/doc/rust/* /w/std-docs/
        cd $SYS/lib/rustlib/src/rust && find library -maxdepth 3 \\( -iname "LICENSE*" -o -iname "COPYRIGHT*" \\) \\
            -exec cp --parents {} /w/rustsrc-lic/ \\;
        cd /w/src/common && cargo fetch --locked
        for t in aarch64-linux-android x86_64-linux-android; do
            cargo tree --locked -p ferrostar --target $t -e normal --prefix none --no-dedupe -f "{p}|{l}" \\
                | sort -u > /w/tree-$t.txt
            (cd $SYS/lib/rustlib/src/rust/library && RUSTC_BOOTSTRAP=1 cargo tree --locked -p std \\
                --features "backtrace panic-unwind" --target $t -e normal --prefix none --no-dedupe -f "{p}|{l}" \\
                | sort -u > /w/std-tree-$t.txt)
        done'
    python scripts/rust_notices.py <työkansio> <ferrostarin versio> app/src/main/res/raw/notice_ferrostar_rust.txt

`cargo tree -e normal` antaa Android-kohteiden tavalliset riippuvuudet (ei build- eikä dev-riippuvuuksia).
Jokaisen paketin lisenssitiedostot otetaan paketista sellaisenaan; jos julkaistusta paketista puuttuu
lisenssitiedosto, teksti haetaan saman version lähderepositoriosta (UPSTREAM). Standardikirjaston omat osat
saavat Rustin tekijänoikeusselosteen (COPYRIGHT-library, kohta "In-tree files") ja lisenssitekstit. Samat
tekstit näytetään kerran. Pakettiluettelo kirjoitetaan myös kansioon scripts/rust-crates/, johon RustNoticesTest
vertaa merkintöjä.
"""
import html
import pathlib
import re
import sys
import urllib.request

LICENSE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|UNLICENSE)([-_.].*)?$", re.IGNORECASE)
TARGETS = ["aarch64-linux-android", "x86_64-linux-android"]
CRATES_DIR = pathlib.Path(__file__).resolve().parent / "rust-crates"

# Paketit, joiden julkaistusta versiosta lisenssitiedosto puuttuu: (versio, repositorio, tagi, tiedostot).
UPSTREAM = {
    "geo": ("0.33.1", "georust/geo", "geo-0.33.1", ["LICENSE-APACHE", "LICENSE-MIT"]),
    "geo-types": ("0.7.19", "georust/geo", "geo-types-0.7.19", ["LICENSE-APACHE", "LICENSE-MIT"]),
    "i_key_sort": ("0.10.1", "iShape-Rust/iKeySort", "0.10.1", ["LICENSE"]),
    "i_overlay": ("4.5.1", "iShape-Rust/iOverlay", "4.5.1", ["LICENSE-APACHE", "LICENSE-MIT"]),
    "rstar": ("0.12.2", "georust/rstar", "v0.12.2", ["LICENSE-APACHE", "LICENSE-MIT"]),
    **{
        name: ("0.31.1", "mozilla/uniffi-rs", "v0.31.1", ["LICENSE"])
        for name in ["uniffi", "uniffi_core", "uniffi_internal_macros", "uniffi_macros", "uniffi_meta", "uniffi_pipeline"]
    },
}

# Paketit, joiden lisenssitiedosto vain viittaa lisenssiin: perään MIT-teksti paketin omilla tekijänoikeusriveillä.
POINTER_ONLY_MIT = {"siphasher"}

# Standardikirjaston lähdepuussa olevien osien lisenssitiedostot (rust-src, kansio library/).
STD_IN_TREE_FILES = {
    "core": ["portable-simd/LICENSE-APACHE", "portable-simd/LICENSE-MIT"],
    "std": ["backtrace/LICENSE-APACHE", "backtrace/LICENSE-MIT"],
    "std_detect": ["stdarch/LICENSE-APACHE", "stdarch/LICENSE-MIT"],
    "compiler_builtins": ["compiler-builtins/LICENSE.txt", "compiler-builtins/libm/LICENSE.txt"],
}

# Täyden lisenssitekstin tunnisteet: pelkkä viittaus ("Licensed under ... MIT") ei riitä.
FULL_TEXT_MARKERS = [
    "Permission is hereby granted",  # MIT, Unicode
    "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",  # Apache-2.0
    "Mozilla Public License Version 2.0",
    "Permission to use, copy, modify, and/or distribute",  # ISC, 0BSD
    "This software is provided 'as-is'",  # Zlib
    "This is free and unencumbered software",  # Unlicense
    "Redistribution and use in source and binary forms",  # BSD
]

TREE_LINE = re.compile(r"^(?P<name>\S+) v(?P<version>[^\s|]+)(?P<path> \(/[^)]*\))?(?: \([^)/][^)]*\))*\|(?P<license>[^|]*)")


def read_tree(path: pathlib.Path, rust_version: str) -> dict[tuple[str, str], dict]:
    found = {}
    for line in path.read_bytes().decode().splitlines():
        match = TREE_LINE.match(line.strip())
        if not match:
            sys.exit(f"Tuntematon rivi: {line}")
        # Standardikirjaston omien osien versio on lähdepuussa 0.0.0: niille näytetään Rustin versio.
        version = rust_version if match["path"] and match["version"] == "0.0.0" else match["version"]
        crate = {"name": match["name"], "version": version, "license": match["license"].strip(), "in_tree": bool(match["path"])}
        found[(crate["name"], version)] = crate
    return found


def crates(workdir: pathlib.Path, prefix: str, rust_version: str, skip: set[str]) -> list[dict]:
    merged = {}
    for target in TARGETS:
        merged.update(read_tree(workdir / f"{prefix}tree-{target}.txt", rust_version))
    return [c for key, c in sorted(merged.items()) if c["name"] not in skip]


def clean(raw: bytes) -> str:
    return raw.decode("utf-8", "replace").replace("\r\n", "\n").strip()


def folder_texts(folder: pathlib.Path) -> list[str]:
    files = sorted((f for f in folder.iterdir() if f.is_file() and LICENSE_FILE.match(f.name)), key=lambda f: f.name.lower())
    return [text for text in (clean(f.read_bytes()) for f in files) if text]


def registry_texts(workdir: pathlib.Path, crate: dict) -> list[str]:
    found = list(workdir.glob(f'cargo/registry/src/*/{crate["name"]}-{crate["version"]}'))
    if len(found) != 1:
        sys.exit(f'{crate["name"]} {crate["version"]}: lähdekansio puuttuu, aja cargo fetch')
    return folder_texts(found[0])


def upstream_texts(crate: dict, workdir: pathlib.Path) -> list[str]:
    entry = UPSTREAM.get(crate["name"])
    if entry is None:
        return []
    version, repo, ref, files = entry
    if version != crate["version"]:
        sys.exit(f'{crate["name"]}: UPSTREAM on versiolle {version}, Cargo.lockissa {crate["version"]}')
    texts = []
    for name in files:
        cache = workdir / "upstream" / repo.replace("/", "_") / ref / name
        if not cache.exists():
            url = f"https://raw.githubusercontent.com/{repo}/{ref}/{name}"
            request = urllib.request.Request(url, headers={"User-Agent": "tyomatka-notices/1.0"})
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_bytes(body)
        texts.append(clean(cache.read_bytes()))
    return texts


def canonical(workdir: pathlib.Path, spdx: str) -> str:
    return clean((workdir / "std-docs" / "licenses" / f"{spdx}.txt").read_bytes())


def mit_with(workdir: pathlib.Path, copyright_lines: list[str]) -> str:
    template = "Copyright (c) <year> <copyright holders>"
    text = canonical(workdir, "MIT")
    if text.count(template) != 1:
        sys.exit("MIT-pohjan tekijänoikeusrivi puuttuu")
    return text.replace(template, "\n".join(copyright_lines))


def in_tree_notice(workdir: pathlib.Path) -> str:
    """Rustin oma seloste standardikirjaston lähdepuun tiedostoista (COPYRIGHT-library, "In-tree files")."""
    raw = (workdir / "std-docs" / "COPYRIGHT-library.html").read_bytes().decode("utf-8")
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", raw, flags=re.S)
    text = re.sub(r"<br\s*/?>|</p>|</li>|</h\d>|</div>|</tr>", "\n", text)
    text = html.unescape(re.sub(r"<[^>]+>", "", text))
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(line.strip() for line in text.splitlines()))
    start = text.index("Short version for non-lawyers\n\nThe Rust")
    end = text.index("Out-of-tree dependencies", start)
    return text[start:end].strip()


def std_texts(workdir: pathlib.Path, crate: dict, notice: str) -> list[str]:
    if crate["in_tree"]:
        library = workdir / "rustsrc-lic" / "library"
        texts = [clean((library / name).read_bytes()) for name in STD_IN_TREE_FILES.get(crate["name"], [])]
        if crate["name"] in ("compiler_builtins", "std_detect"):
            return texts
        extra = [canonical(workdir, "Unicode-3.0")] if crate["name"] == "core" else []
        rust = [notice, mit_with(workdir, ["Copyright (c) The Rust Project Developers"]), canonical(workdir, "Apache-2.0")]
        return rust + extra + texts
    vendor = workdir / "rustsrc-lic" / "library" / "vendor" / f'{crate["name"]}-{crate["version"]}'
    return folder_texts(vendor) if vendor.is_dir() else registry_texts(workdir, crate)


def has_full_text(texts: list[str]) -> bool:
    return any(marker in text for text in texts for marker in FULL_TEXT_MARKERS)


def main() -> None:
    workdir, version, out = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3])
    rust_full = clean((workdir / "rustc-version.txt").read_bytes())
    rust_version = rust_full.split()[1]
    ferrostar = crates(workdir, "", rust_version, {"ferrostar"})
    std = crates(workdir, "std-", rust_version, set())
    notice = in_tree_notice(workdir)

    groups: dict[str, list[str]] = {}
    problems = []
    for crate, texts in [(c, registry_texts(workdir, c) or upstream_texts(c, workdir)) for c in ferrostar] + [
        (c, std_texts(workdir, c, notice)) for c in std
    ]:
        label = f'{crate["name"]} {crate["version"]}'
        if crate["name"] in POINTER_ONLY_MIT:
            texts = texts + [mit_with(workdir, [l for t in texts for l in t.splitlines() if l.startswith("Copyright")])]
        if not has_full_text(texts):
            problems.append(label)
        for text in texts:
            group = groups.setdefault(text, [])
            # Sama paketti voi olla sekä Ferrostarin että standardikirjaston puussa (cfg-if).
            if label not in group:
                group.append(label)
    if problems:
        sys.exit("Täysi lisenssiteksti puuttuu: " + ", ".join(problems))

    def listing(items: list[dict]) -> list[str]:
        return [f'{c["name"]} {c["version"]} ({c["license"] or "see license text"})' for c in items]

    mpl = [c for c in ferrostar + std if "MPL" in c["license"]]
    lines = [
        f"Ferrostar {version}: libferrostar.so",
        "",
        f"The native navigation core is compiled from Rust ({rust_full}) and includes the following crates",
        "and the Rust standard library. Their license texts follow, each shown once with the crates it applies to.",
        "",
        "Crates:",
        *listing(ferrostar),
        "",
        "Rust standard library:",
        *listing(std),
    ]
    if mpl:
        lines += ["", "Source code of the MPL-2.0 crates:"]
        lines += [f'{c["name"]} {c["version"]}: https://crates.io/crates/{c["name"]}/{c["version"]}' for c in mpl]
    for text, labels in groups.items():
        lines += ["", "-" * 40, ", ".join(labels), "-" * 40, "", text]
    out.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    CRATES_DIR.mkdir(exist_ok=True)
    for name, items in (("ferrostar", ferrostar), ("std", std)):
        listed = [f"# cargo tree -e normal, {', '.join(TARGETS)}, {rust_full}"]
        listed += [f'{c["name"]} {c["version"]}' for c in items]
        (CRATES_DIR / f"{name}.txt").write_bytes(("\n".join(listed) + "\n").encode("utf-8"))
    print(f"{len(ferrostar)} + {len(std)} pakettia, {len(groups)} erillistä tekstiä, {out.stat().st_size} tavua")


if __name__ == "__main__":
    main()
